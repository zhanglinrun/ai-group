from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from agents.nodes.planner import (
    _assert_research_competitor_subset,
    _cap_plan_tasks_for_profile,
    _research_competitors_from_tasks,
)
from agents.state import AgentState, spread_without_accumulators
from agents.state_coercion import coerce_intake_draft_or_default, coerce_plan_tree
from core.defaults import MAX_TOTAL_PLAN_TASKS
from core.tiers import resolve_tier_profile
from db.engine import get_session_factory
from models.run import Run
from models.step import Step
from schemas.agent_outputs import ReplannerOutput
from schemas.ids import make_id
from schemas.plan import PlanTask, PlanTree
from service.event_bus import RunEventType, emit_run_event
from service.llm import (
    REPLANNER_SYSTEM_PROMPT,
    build_replanner_fallback_user_prompt,
    build_replanner_repair_user_prompt,
    build_replanner_user_prompt,
)
from service.llm.harness import complete_structured
from service.llm.records import build_llm_call_record
from service.llm.response import LLMResponse
from utils.log_node import log_node
from utils.logger import bind_step, get_logger

log = get_logger("agents.replanner")

TriggerReason = Literal["discovery", "qa_rejection"]


def _resolve_session_factory(state: AgentState) -> async_sessionmaker[AsyncSession]:
    del state
    return get_session_factory()


def _state_trigger_reason(state: AgentState) -> TriggerReason:
    return "qa_rejection" if state.get("qa_outcome") == "rejected" else "discovery"


def _is_task_completed(
    task: PlanTask,
    *,
    discovered_competitors: list[str],
    researched_competitors: list[str],
    analysis_done: bool,
    report_draft_done: bool,
) -> bool:
    if task.stage == "discover":
        return len(discovered_competitors) > 0
    if task.stage == "research":
        return task.competitor_id in set(researched_competitors)
    if task.stage == "analyze":
        return analysis_done
    if task.stage == "write":
        return report_draft_done
    return False


def _partition_plan_tasks(
    plan: PlanTree,
    *,
    discovered_competitors: list[str],
    researched_competitors: list[str],
    analysis_done: bool,
    report_draft_done: bool,
) -> tuple[list[PlanTask], list[PlanTask]]:
    completed: list[PlanTask] = []
    pending: list[PlanTask] = []
    for task in plan.tasks:
        if _is_task_completed(
            task,
            discovered_competitors=discovered_competitors,
            researched_competitors=researched_competitors,
            analysis_done=analysis_done,
            report_draft_done=report_draft_done,
        ):
            completed.append(task)
            continue
        pending.append(task)
    return completed, pending


def _sanitize_revised_pending_tasks(
    tasks: list[PlanTask],
    *,
    discovered_competitors: list[str],
    researched_competitors: list[str],
    appendable_research_competitors: set[str],
    protected_research_competitors: set[str],
) -> list[PlanTask]:
    researched_set = set(researched_competitors)
    normalized: list[PlanTask] = []
    seen_signatures: set[tuple[str, str, str]] = set()
    seen_research_competitors: set[str] = set()
    for task in tasks:
        if task.stage == "discover" and discovered_competitors:
            continue
        competitor_id = task.competitor_id or ""
        if task.stage == "research":
            if not competitor_id or competitor_id in researched_set:
                continue
            if competitor_id in protected_research_competitors:
                continue
            if competitor_id not in appendable_research_competitors:
                continue
            if competitor_id in seen_research_competitors:
                continue
            seen_research_competitors.add(competitor_id)
        signature = (task.stage, competitor_id, task.title.strip().casefold())
        if signature in seen_signatures:
            continue
        seen_signatures.add(signature)
        normalized.append(task)
    return normalized


def _collect_protected_pending_research_tasks(
    *,
    pending_tasks: list[PlanTask],
    protected_competitors: set[str],
) -> list[PlanTask]:
    protected: list[PlanTask] = []
    seen_competitors: set[str] = set()
    for task in pending_tasks:
        if task.stage != "research":
            continue
        competitor_id = task.competitor_id.strip() if isinstance(task.competitor_id, str) else ""
        if not competitor_id or competitor_id not in protected_competitors:
            continue
        if competitor_id in seen_competitors:
            continue
        seen_competitors.add(competitor_id)
        protected.append(task)
    return protected


async def _persist_replanner_step(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    action: str,
    trigger_reason: TriggerReason,
    previous_plan: PlanTree,
    revised_plan: PlanTree,
    llm_response: LLMResponse,
    reasoning_summary: str,
) -> str:
    async with session_factory() as session:
        step = Step(
            step_id=make_id("step_"),
            run_id=run_id,
            agent_name="replanner",
            status="running",
            retry_count=0,
            payload={
                "phase": "planning",
                "action": action,
                "trigger_reason": trigger_reason,
                "previous_task_count": len(previous_plan.tasks),
                "revised_task_count": len(revised_plan.tasks),
                "plan_id": revised_plan.plan_id,
                "plan_version": revised_plan.version,
                "llm_provider": llm_response.provider,
                "llm_fallback_used": llm_response.fallback_used,
                "llm_fallback_reason": llm_response.fallback_reason,
                "reasoning_summary": reasoning_summary[:1000] if reasoning_summary else "",
            },
        )
        session.add(step)
        await session.flush()
        session.add(build_llm_call_record(step_id=step.step_id, response=llm_response))
        step.status = "completed"
        step.finished_at = datetime.now(timezone.utc)
        await session.commit()
        return step.step_id


async def _persist_plan_tree_to_run(*, run_id: str, plan: PlanTree) -> None:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        run.plan_tree = plan.model_dump()
        await session.commit()


@log_node("replanner")
async def replanner_node(state: AgentState) -> AgentState:
    run_id = state.get("run_id") or make_id("run_")
    plan = coerce_plan_tree(state.get("plan_tree"))
    if plan is None:
        log.info("replanner.skip.no_plan_tree", run_id=run_id)
        return {
            **spread_without_accumulators(state),
            "run_id": run_id,
        }
    draft = coerce_intake_draft_or_default(state)
    tier_profile = resolve_tier_profile(draft.report_depth)
    current_replan_count = int(state.get("replan_count", 0))
    if current_replan_count >= tier_profile.replan_budget:
        log.info(
            "replanner.skip.budget_reached",
            run_id=run_id,
            replan_count=current_replan_count,
            replan_budget=tier_profile.replan_budget,
        )
        return {
            **spread_without_accumulators(state),
            "run_id": run_id,
            "replan_count": current_replan_count,
        }
    next_replan_count = current_replan_count + 1
    trigger_reason = _state_trigger_reason(state)
    state_competitors = [item for item in state.get("competitors", []) if isinstance(item, str)]
    researched_competitors = [
        item for item in state.get("researched_competitors", []) if isinstance(item, str)
    ]
    discovered_competitors = [
        item for item in state.get("discovered_competitors", []) if isinstance(item, str)
    ]
    analysis_done = bool(state.get("analysis_done", False))
    report_draft_done = bool(state.get("report_draft_done", False))
    qa_reasons = [item for item in state.get("qa_reasons", []) if isinstance(item, str)]

    completed_tasks, pending_tasks = _partition_plan_tasks(
        plan,
        discovered_competitors=discovered_competitors,
        researched_competitors=researched_competitors,
        analysis_done=analysis_done,
        report_draft_done=report_draft_done,
    )
    if not pending_tasks:
        log.info("replanner.skip.no_pending_tasks", run_id=run_id, trigger_reason=trigger_reason)
        return {
            **spread_without_accumulators(state),
            "run_id": run_id,
            "replan_count": next_replan_count,
        }

    session_factory = _resolve_session_factory(state)
    intake_dump = draft.model_dump(exclude={"is_complete"})
    plan_dump = plan.model_dump()
    user_prompt = build_replanner_user_prompt(
        intake_draft=intake_dump,
        current_plan_tree=plan_dump,
        trigger_reason=trigger_reason,
        state_competitors=state_competitors,
        researched_competitors=researched_competitors,
        discovered_competitors=discovered_competitors,
        analysis_done=analysis_done,
        report_draft_done=report_draft_done,
        qa_reasons=qa_reasons,
    )
    fallback_user_prompt = build_replanner_fallback_user_prompt(
        intake_draft=intake_dump,
        current_plan_tree=plan_dump,
        trigger_reason=trigger_reason,
    )
    harness_result = await complete_structured(
        model_slot="research",
        system_prompt=REPLANNER_SYSTEM_PROMPT,
        user_prompt=user_prompt,
        output_model=ReplannerOutput,
        parser=lambda content: ReplannerOutput.parse_llm_content(content, draft=draft),
        fallback_system_prompt=REPLANNER_SYSTEM_PROMPT,
        fallback_user_prompt=fallback_user_prompt,
        repair_user_prompt_builder=lambda errors: build_replanner_repair_user_prompt(
            validation_errors=errors,
            intake_draft=intake_dump,
            current_plan_tree=plan_dump,
        ),
        log_event="replanner.harness.finish",
    )
    llm_response = harness_result.llm_response
    revised_plan = plan
    action = "no_change"
    reasoning_summary = ""
    protected_competitors = {
        item.strip()
        for item in [*state_competitors, *researched_competitors]
        if isinstance(item, str) and item.strip()
    }
    protected_pending_research = _collect_protected_pending_research_tasks(
        pending_tasks=pending_tasks,
        protected_competitors=protected_competitors,
    )
    protected_pending_research_competitors = {
        task.competitor_id.strip()
        for task in protected_pending_research
        if isinstance(task.competitor_id, str) and task.competitor_id.strip()
    }
    appendable_research_competitors = {
        item.strip()
        for item in discovered_competitors
        if isinstance(item, str) and item.strip()
    }
    appendable_research_competitors -= protected_pending_research_competitors
    appendable_research_competitors -= {
        item.strip()
        for item in researched_competitors
        if isinstance(item, str) and item.strip()
    }

    if harness_result.value is not None:
        reasoning_summary = harness_result.value.rationale
        revised_pending = harness_result.value.to_plan_tasks()
        completed_research_count = len(
            [
                task
                for task in completed_tasks
                if task.stage == "research" and task.competitor_id is not None
            ]
        )
        max_competitors_remaining = max(
            tier_profile.max_competitors - completed_research_count,
            0,
        )
        capped_pending = _cap_plan_tasks_for_profile(
            revised_pending,
            analysis_archetype=draft.analysis_archetype,
            max_competitors=max_competitors_remaining,
            max_dimensions=tier_profile.max_dimensions,
        )
        capped_pending = _sanitize_revised_pending_tasks(
            capped_pending,
            discovered_competitors=discovered_competitors,
            researched_competitors=researched_competitors,
            appendable_research_competitors=appendable_research_competitors,
            protected_research_competitors=protected_pending_research_competitors,
        )
        remaining_slots = max(MAX_TOTAL_PLAN_TASKS - len(completed_tasks), 0)
        max_task_count = len(completed_tasks) + remaining_slots
        merged_tasks = list(completed_tasks)
        seen_signatures = {
            (
                item.stage,
                item.competitor_id or "",
                item.title.strip().casefold(),
            )
            for item in merged_tasks
        }
        for task in [*protected_pending_research, *capped_pending]:
            if len(merged_tasks) >= max_task_count:
                break
            signature = (
                task.stage,
                task.competitor_id or "",
                task.title.strip().casefold(),
            )
            if signature in seen_signatures:
                continue
            seen_signatures.add(signature)
            merged_tasks.append(task)
        if merged_tasks and merged_tasks != list(plan.tasks):
            revised_plan = plan.model_copy(
                update={
                    "tasks": merged_tasks,
                    "rationale": reasoning_summary or plan.rationale,
                    "version": plan.version + 1,
                }
            )
            action = "revise"

    revised_research_competitors = _research_competitors_from_tasks(list(revised_plan.tasks))
    _assert_research_competitor_subset(
        actual_competitors=revised_research_competitors,
        allowed_competitors=[*state_competitors, *discovered_competitors],
        context="replanner.output_plan",
    )
    state_competitor_set = {
        item.strip() for item in state_competitors if isinstance(item, str) and item.strip()
    }
    competitor_sync_delta = [
        competitor
        for competitor in revised_research_competitors
        if competitor not in state_competitor_set
    ]

    step_id = await _persist_replanner_step(
        session_factory=session_factory,
        run_id=run_id,
        action=action,
        trigger_reason=trigger_reason,
        previous_plan=plan,
        revised_plan=revised_plan,
        llm_response=llm_response,
        reasoning_summary=reasoning_summary,
    )
    if action == "revise":
        await _persist_plan_tree_to_run(run_id=run_id, plan=revised_plan)
        with bind_step(step_id):
            log.info(
                "replanner.publish",
                run_id=run_id,
                trigger_reason=trigger_reason,
                previous_version=plan.version,
                revised_version=revised_plan.version,
                task_count=len(revised_plan.tasks),
            )
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.PLAN_REVISED,
            step_id=step_id,
            payload={
                "plan_id": revised_plan.plan_id,
                "task_count": len(revised_plan.tasks),
                "version": revised_plan.version,
                "trigger_reason": trigger_reason,
                "plan_tree": revised_plan.model_dump(),
            },
        )
    else:
        with bind_step(step_id):
            log.info(
                "replanner.no_change",
                run_id=run_id,
                trigger_reason=trigger_reason,
                plan_version=plan.version,
            )

    result: dict[str, object] = {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "replan_count": next_replan_count,
    }
    if competitor_sync_delta:
        with bind_step(step_id):
            log.warning(
                "replanner.sync.competitors_from_plan",
                run_id=run_id,
                synced_competitors=competitor_sync_delta,
            )
        result["competitors"] = competitor_sync_delta
    if action == "revise":
        result["plan_tree"] = revised_plan
    return result
