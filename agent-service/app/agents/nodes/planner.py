from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timezone
from typing import Any

from langgraph.types import interrupt
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from agents.state import AgentState, spread_without_accumulators
from agents.state_coercion import (
    coerce_intake_draft_or_default,
    coerce_pending_plan_tree,
    coerce_plan_tree,
)
from core.defaults import (
    DEFAULT_FOCUS_DIMENSIONS,
    MAX_ADDITIONAL_PLAN_TASKS,
    MAX_FOCUS_DIMENSIONS,
    PLAN_TASK_DESCRIPTION_MAX_LEN,
    PLAN_TASK_TITLE_MAX_LEN,
    MAX_RESEARCH_COMPETITORS,
    MAX_TOTAL_PLAN_TASKS,
)
from core.tiers import resolve_tier_profile
from db.engine import get_session_factory
from models.run import Run
from models.step import Step
from schemas.agent_outputs import PlannerOutput
from schemas.contracts import (
    COMPARISON_SCHEMA_BASE_DIMENSIONS,
    ensure_comparison_schema_dimensions,
    normalize_dimensions,
    research_focus_dimensions,
)
from schemas.ids import make_id
from schemas.intake import IntakeUserReply, RunIntakeDraft
from schemas.plan import PlanConfirmRequest, PlanTask, PlanTaskStage, PlanTree
from service.event_bus import RunEventType, emit_run_event
from service.llm import (
    PLANNER_SYSTEM_PROMPT,
    build_planner_fallback_user_prompt,
    build_planner_repair_user_prompt,
    build_planner_user_prompt,
)
from service.llm.harness import complete_structured
from service.llm.records import build_llm_call_record
from service.llm.response import LLMResponse
from utils.log_node import log_node
from utils.logger import bind_step, get_logger

log = get_logger("agents.planner")

# Phase β: user injections never include "discover" — that stage is the
# discovery node's exclusive output. Allowing it would let two discoveries
# compete and would also bypass `_derive_focus_dimensions`.
_USER_ALLOWED_STAGES: frozenset[str] = frozenset({"research", "analyze", "write"})
_PLAN_STAGE_ORDER: tuple[PlanTaskStage, ...] = ("discover", "research", "analyze", "write")
_COMPETITOR_ROLE_LABELS: dict[str, str] = {
    "direct_competitor": "核心竞争样本",
    "adjacent_competitor": "相邻样本",
    "substitute": "替代路径",
    "upstream_supplier": "上游供应商",
    "trend_reference": "趋势参考",
}
_CORE_DISCOVERY_ROLES: frozenset[str] = frozenset(
    {"direct_competitor", "adjacent_competitor", "substitute"}
)
_COMPARISON_SCHEMA_DIMENSIONS_SET: frozenset[str] = frozenset(COMPARISON_SCHEMA_BASE_DIMENSIONS)
_REPORT_DEPTH_KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("debug", ("debug", "调试", "极速", "超快")),
    ("quick", ("quick", "速览", "快速", "标准", "平衡")),
    ("deep", ("deep", "深度", "完整", "详细")),
)


def _resolve_session_factory(state: AgentState) -> async_sessionmaker[AsyncSession]:
    return get_session_factory()


def _build_report_depth_selection_interrupt() -> dict[str, object]:
    return {
        "kind": "report_depth_select",
        "question": "需求已明确。开始生成计划前，请选择分析档位。",
        "field_targets": ["report_depth"],
        "suggested_options": ["quick", "deep", "debug"],
        "suggested_answer": "quick",
    }


def _match_report_depth(value: str) -> str | None:
    needle = value.casefold().strip()
    if not needle:
        return None
    for depth, keywords in _REPORT_DEPTH_KEYWORDS:
        if any(keyword.casefold() in needle for keyword in keywords):
            return depth
    return None


def _resolve_report_depth_from_reply(reply: IntakeUserReply) -> str:
    candidates = [*reply.selected_options, reply.text]
    for candidate in candidates:
        depth = _match_report_depth(candidate)
        if depth is not None:
            return depth
    raise RuntimeError(
        "planning_profile_wait reply does not contain a valid report_depth "
        f"(selected_options={reply.selected_options}, text={reply.text!r})"
    )


def _coerce_pending_plan(state: AgentState) -> PlanTree:
    return coerce_pending_plan_tree(state)


def _canonical_focus_dimensions(
    values: list[str],
    *,
    analysis_archetype: str = "comparison",
    max_dimensions: int,
) -> list[str]:
    normalized = normalize_dimensions(values, allow_empty=True)
    if not normalized:
        normalized = list(DEFAULT_FOCUS_DIMENSIONS)
    canonical = ensure_comparison_schema_dimensions(
        normalized,
        analysis_archetype=analysis_archetype,
    )
    if analysis_archetype == "comparison":
        extras = [
            dimension
            for dimension in canonical
            if dimension not in _COMPARISON_SCHEMA_DIMENSIONS_SET
        ]
        ordered = [*COMPARISON_SCHEMA_BASE_DIMENSIONS, *extras]
        # Focused comparison runs must keep the schema core dimensions intact even when
        # max_dimensions is configured to a lower cap.
        cap = max(max_dimensions, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
        return ordered[:cap]
    return canonical[:max_dimensions]


def _fallback_tasks(
    draft: RunIntakeDraft,
    *,
    max_competitors: int,
    max_dimensions: int,
) -> list[PlanTask]:
    """Deterministic plan when the LLM output is unusable.

    Mirrors the supervisor's reachable execution path so the visible plan
    never lies about what the executor would do.
    """
    focus = _canonical_focus_dimensions(
        list(draft.focus_dimensions),
        analysis_archetype=draft.analysis_archetype,
        max_dimensions=max_dimensions,
    )
    research_focus = research_focus_dimensions(
        focus,
        analysis_archetype=draft.analysis_archetype,
    )[:max_dimensions]
    tasks: list[PlanTask] = []
    is_landscape = draft.analysis_archetype == "landscape"
    competitors = list(draft.competitors_explicit)
    if is_landscape or draft.competitors_discovery_mode or not competitors:
        tasks.append(
            PlanTask(
                stage="discover",
                title="发现赛道头部竞品",
                description="基于用户问题在公开渠道检索可能的头部竞品。",
                competitor_id=None,
                focus_dimensions=research_focus,
            )
        )
    for competitor in competitors[:max_competitors]:
        tasks.append(
            PlanTask(
                stage="research",
                title=f"调研 {competitor}"[:PLAN_TASK_TITLE_MAX_LEN],
                description=f"按维度收集 {competitor} 的事实证据。",
                competitor_id=competitor,
                focus_dimensions=research_focus,
            )
        )
    if is_landscape:
        tasks.append(
            PlanTask(
                stage="analyze",
                title="机会地图分析",
                description="基于证据梳理赛道机会空白与进入路径。",
                competitor_id=None,
                focus_dimensions=focus,
            )
        )
        tasks.append(
            PlanTask(
                stage="analyze",
                title="趋势与格局分析",
                description="识别赛道演进趋势、竞争格局与关键变量。",
                competitor_id=None,
                focus_dimensions=focus,
            )
        )
    else:
        tasks.append(
            PlanTask(
                stage="analyze",
                title="跨竞品对比分析",
                description="基于证据生成跨竞品对比与差异化洞察。",
                competitor_id=None,
                focus_dimensions=focus,
            )
        )
    tasks.append(
        PlanTask(
            stage="write",
            title="生成竞品分析报告",
            description="按用户角色和关注维度撰写报告。",
            competitor_id=None,
            focus_dimensions=focus,
        )
    )
    return tasks[:MAX_TOTAL_PLAN_TASKS]


def _cap_plan_tasks_for_profile(
    tasks: list[PlanTask],
    *,
    analysis_archetype: str,
    max_competitors: int,
    max_dimensions: int,
) -> list[PlanTask]:
    capped: list[PlanTask] = []
    research_count = 0
    for task in tasks:
        competitor_id = (
            task.competitor_id.strip()
            if isinstance(task.competitor_id, str) and task.competitor_id.strip()
            else None
        )
        if task.stage == "research":
            if competitor_id is None:
                continue
            if research_count >= max_competitors:
                continue
            research_count += 1

        focus_dimensions = _canonical_focus_dimensions(
            list(task.focus_dimensions),
            analysis_archetype=analysis_archetype,
            max_dimensions=max_dimensions,
        )
        if task.stage in {"discover", "research"}:
            focus_dimensions = research_focus_dimensions(
                focus_dimensions,
                analysis_archetype=analysis_archetype,
            )[:max_dimensions]

        capped.append(
            task.model_copy(
                update={
                    "competitor_id": competitor_id if task.stage == "research" else None,
                    "focus_dimensions": focus_dimensions,
                }
            )
        )
        if len(capped) >= MAX_TOTAL_PLAN_TASKS:
            break
    return capped


def _merge_plan_tasks_with_user_priority(
    *,
    kept_tasks: list[PlanTask],
    user_tasks: list[PlanTask],
) -> list[PlanTask]:
    """Merge plan tasks while preserving stage order and prioritizing user tasks.

    Why: if we append user tasks after agent tasks and then apply tier caps
    (especially debug max_competitors=2), user-injected research tasks can be
    silently dropped. We place user tasks first *within each stage* so caps
    evict lower-priority agent tasks before user_pinned tasks.
    """
    merged: list[PlanTask] = []
    for stage in _PLAN_STAGE_ORDER:
        merged.extend(task for task in user_tasks if task.stage == stage)
        merged.extend(task for task in kept_tasks if task.stage == stage)
    return merged


def _research_competitors_from_tasks(tasks: list[PlanTask]) -> list[str]:
    competitors: list[str] = []
    seen: set[str] = set()
    for task in tasks:
        if task.stage != "research" or task.competitor_id is None:
            continue
        competitor_id = task.competitor_id.strip()
        if not competitor_id or competitor_id in seen:
            continue
        seen.add(competitor_id)
        competitors.append(competitor_id)
    return competitors


def _is_placeholder_research_competitor(value: str | None) -> bool:
    if not isinstance(value, str):
        return False
    normalized = value.strip().casefold()
    return normalized.startswith("representative_competitor")


def _assert_research_competitor_subset(
    *,
    actual_competitors: Sequence[str],
    allowed_competitors: Sequence[str],
    context: str,
) -> None:
    allowed_set = {
        item.strip()
        for item in allowed_competitors
        if isinstance(item, str) and item.strip()
    }
    actual = [
        item.strip()
        for item in actual_competitors
        if isinstance(item, str) and item.strip()
    ]
    unexpected = [item for item in actual if item not in allowed_set]
    if not unexpected:
        return
    log.error(
        "planner.invariant.research_competitor_subset_failed",
        context=context,
        unexpected_research_competitors=unexpected,
        actual_research_competitors=actual,
        allowed_competitors=sorted(allowed_set),
    )
    raise ValueError(
        "plan research competitors escaped the allowed competitor set "
        f"(context={context}, unexpected={unexpected})"
    )


def _research_description_for_discovered_competitor(
    *,
    competitor: str,
    source_payload: dict[str, str | None] | None,
    is_landscape_core_deepdive: bool,
) -> str:
    if is_landscape_core_deepdive:
        return f"按关键维度深挖 {competitor}（功能、定价、用户反馈），用于核心代表层对比。"
    role_raw = source_payload.get("candidate_role") if isinstance(source_payload, dict) else None
    role_label = _COMPETITOR_ROLE_LABELS.get(role_raw or "")
    if role_label is None:
        return f"按维度收集 {competitor} 的事实证据。"
    return f"按维度收集 {competitor} 的事实证据；候选角色：{role_label}。"


def _discovered_competitor_role(
    *,
    competitor: str,
    discovered_competitor_sources: dict[str, dict[str, str | None]] | None,
) -> str | None:
    if not isinstance(discovered_competitor_sources, dict):
        return None
    source_payload = discovered_competitor_sources.get(competitor)
    if not isinstance(source_payload, dict):
        return None
    role_raw = source_payload.get("candidate_role")
    if not isinstance(role_raw, str):
        return None
    role = role_raw.strip()
    return role if role else None


def _discovered_competitor_segment(
    *,
    competitor: str,
    discovered_competitor_sources: dict[str, dict[str, str | None]] | None,
) -> str | None:
    if not isinstance(discovered_competitor_sources, dict):
        return None
    source_payload = discovered_competitor_sources.get(competitor)
    if not isinstance(source_payload, dict):
        return None
    segment_raw = source_payload.get("segment")
    if not isinstance(segment_raw, str):
        return None
    segment = segment_raw.strip()
    return segment if segment else None


def _competitor_segment_key(
    *,
    competitor: str,
    discovered_competitor_sources: dict[str, dict[str, str | None]] | None,
) -> str:
    segment = _discovered_competitor_segment(
        competitor=competitor,
        discovered_competitor_sources=discovered_competitor_sources,
    )
    return segment.casefold() if segment is not None else f"unknown:{competitor.casefold()}"


def _diversify_competitors_by_segment(
    *,
    competitors: list[str],
    discovered_competitor_sources: dict[str, dict[str, str | None]] | None,
) -> list[str]:
    buckets: dict[str, list[str]] = {}
    segment_order: list[str] = []
    for competitor in competitors:
        segment_key = _competitor_segment_key(
            competitor=competitor,
            discovered_competitor_sources=discovered_competitor_sources,
        )
        if segment_key not in buckets:
            buckets[segment_key] = []
            segment_order.append(segment_key)
        buckets[segment_key].append(competitor)
    diversified: list[str] = []
    while len(diversified) < len(competitors):
        advanced = False
        for segment_key in segment_order:
            bucket = buckets[segment_key]
            if not bucket:
                continue
            diversified.append(bucket.pop(0))
            advanced = True
        if not advanced:
            break
    return diversified


def _landscape_core_research_focus_dimensions(
    *,
    base_focus: list[str],
    max_dimensions: int,
) -> list[str]:
    normalized = ensure_comparison_schema_dimensions(
        normalize_dimensions(base_focus, allow_empty=True),
        analysis_archetype="landscape",
        force_schema_dimensions=True,
    )
    extras = [
        dimension
        for dimension in normalized
        if dimension not in _COMPARISON_SCHEMA_DIMENSIONS_SET
    ]
    ordered = [*COMPARISON_SCHEMA_BASE_DIMENSIONS, *extras]
    cap = max(max_dimensions, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
    return ordered[:cap]


def _landscape_peripheral_research_focus_dimensions(
    *,
    base_focus: list[str],
    max_dimensions: int,
) -> list[str]:
    normalized = normalize_dimensions(base_focus, allow_empty=True)
    reduced = [
        dimension
        for dimension in normalized
        if dimension not in _COMPARISON_SCHEMA_DIMENSIONS_SET
    ]
    if not reduced:
        reduced = ["product_positioning", "market_differences"]
    return normalize_dimensions(reduced, allow_empty=True)[:max_dimensions]


def _select_discovered_competitors_for_research(
    *,
    discovered_competitors: list[str],
    discovered_competitor_sources: dict[str, dict[str, str | None]] | None,
    analysis_archetype: str,
    scope_policy: str | None,
    max_competitors: int,
    landscape_core_deepdive_n: int,
) -> tuple[list[str], set[str]]:
    if analysis_archetype != "landscape":
        return discovered_competitors[:max_competitors], set()
    core: list[str] = []
    non_core: list[str] = []
    role_by_competitor: dict[str, str | None] = {}
    for competitor in discovered_competitors:
        role = _discovered_competitor_role(
            competitor=competitor,
            discovered_competitor_sources=discovered_competitor_sources,
        )
        role_by_competitor[competitor] = role
        if role in _CORE_DISCOVERY_ROLES:
            core.append(competitor)
            continue
        non_core.append(competitor)
    if scope_policy == "broad_market":
        # Broad landscape reports should sample across sub-tracks first, then
        # naturally fill remaining slots. Explicit single-category runs skip this.
        core = _diversify_competitors_by_segment(
            competitors=core,
            discovered_competitor_sources=discovered_competitor_sources,
        )
        non_core = _diversify_competitors_by_segment(
            competitors=non_core,
            discovered_competitor_sources=discovered_competitor_sources,
        )
    deepdive_cap = max(0, min(max_competitors, landscape_core_deepdive_n))
    deepdive_core = core[:deepdive_cap]
    if not deepdive_core and deepdive_cap > 0:
        fallback_non_upstream = [
            competitor
            for competitor in non_core
            if role_by_competitor.get(competitor) != "upstream_supplier"
        ]
        deepdive_core = fallback_non_upstream[:deepdive_cap]
    deepdive_core_set = set(deepdive_core)
    remaining_slots = max(0, max_competitors - len(deepdive_core))
    shallow_non_core = [competitor for competitor in non_core if competitor not in deepdive_core_set][
        :remaining_slots
    ]
    remaining_slots -= len(shallow_non_core)
    shallow_core = [competitor for competitor in core if competitor not in deepdive_core_set][
        :remaining_slots
    ]
    selected = [*deepdive_core, *shallow_non_core, *shallow_core]
    return selected, set(deepdive_core)


def reconcile_plan_tree_after_discovery(
    *,
    plan_tree: PlanTree | dict[str, object],
    discovered_competitors: list[str],
    existing_competitors: Sequence[str] | None = None,
    discovered_competitor_sources: dict[str, dict[str, str | None]] | None = None,
    focus_dimensions: list[str] | None = None,
    analysis_archetype: str = "comparison",
    scope_policy: str | None = None,
    max_competitors: int = MAX_RESEARCH_COMPETITORS,
    max_dimensions: int = MAX_FOCUS_DIMENSIONS,
    landscape_core_deepdive_n: int = 3,
) -> PlanTree:
    """Materialize per-competitor research tasks after discovery completes."""
    plan = coerce_plan_tree(plan_tree)
    if plan is None:
        raise ValueError("plan_tree is required to reconcile after discovery.")
    if not discovered_competitors:
        return plan
    if analysis_archetype == "landscape":
        plan = plan.model_copy(
            update={
                "tasks": [
                    task
                    for task in plan.tasks
                    if not (
                        task.stage == "research"
                        and _is_placeholder_research_competitor(task.competitor_id)
                    )
                ]
            }
        )

    existing_research = {
        task.competitor_id
        for task in plan.tasks
        if task.stage == "research" and isinstance(task.competitor_id, str) and task.competitor_id.strip()
    }
    # Existing research tasks are protected plan state. Discovery reconciliation
    # may append newly discovered competitors, but it must not fail simply
    # because an already-confirmed research task uses a display/slug id that is
    # absent from the latest discovery extraction (e.g. beisen vs 北森).
    allowed_competitors = [
        item.strip()
        for item in [
            *(list(existing_competitors or [])),
            *sorted(existing_research),
            *discovered_competitors,
        ]
        if isinstance(item, str) and item.strip()
    ]

    focus = normalize_dimensions(list(focus_dimensions or []), allow_empty=True)
    if not focus:
        for task in plan.tasks:
            if task.focus_dimensions:
                focus = normalize_dimensions(list(task.focus_dimensions), allow_empty=True)
                break
    focus = _canonical_focus_dimensions(
        focus,
        analysis_archetype=analysis_archetype,
        max_dimensions=max_dimensions,
    )

    insert_at = 0
    for index, task in enumerate(plan.tasks):
        if task.stage == "discover":
            insert_at = index + 1
    for index, task in enumerate(plan.tasks):
        if task.stage == "research":
            insert_at = index + 1

    research_focus = research_focus_dimensions(
        focus,
        analysis_archetype=analysis_archetype,
    )
    new_research_tasks: list[PlanTask] = []
    selected_discovered_competitors, deepdive_core_competitors = _select_discovered_competitors_for_research(
        discovered_competitors=discovered_competitors,
        discovered_competitor_sources=discovered_competitor_sources,
        analysis_archetype=analysis_archetype,
        scope_policy=scope_policy,
        max_competitors=max_competitors,
        landscape_core_deepdive_n=landscape_core_deepdive_n,
    )
    for competitor in selected_discovered_competitors:
        if competitor in existing_research:
            continue
        source_payload = (
            discovered_competitor_sources.get(competitor)
            if isinstance(discovered_competitor_sources, dict)
            else None
        )
        is_landscape_core_deepdive = (
            analysis_archetype == "landscape"
            and competitor in deepdive_core_competitors
        )
        competitor_focus = (
            _landscape_core_research_focus_dimensions(
                base_focus=research_focus,
                max_dimensions=max_dimensions,
            )
            if is_landscape_core_deepdive
            else _landscape_peripheral_research_focus_dimensions(
                base_focus=research_focus,
                max_dimensions=max_dimensions,
            )
            if analysis_archetype == "landscape"
            else list(research_focus)[:max_dimensions]
        )
        new_research_tasks.append(
            PlanTask(
                stage="research",
                title=f"调研 {competitor}"[:PLAN_TASK_TITLE_MAX_LEN],
                description=_research_description_for_discovered_competitor(
                    competitor=competitor,
                    source_payload=source_payload,
                    is_landscape_core_deepdive=is_landscape_core_deepdive,
                ),
                competitor_id=competitor,
                focus_dimensions=competitor_focus,
                source="agent",
                enabled=True,
            )
        )

    merged_competitor_sources = dict(plan.competitor_sources)
    if isinstance(discovered_competitor_sources, dict):
        for competitor_id, source_payload in discovered_competitor_sources.items():
            if not isinstance(competitor_id, str) or not competitor_id.strip():
                continue
            if not isinstance(source_payload, dict):
                continue
            official_url_raw = source_payload.get("official_url")
            source_domain_raw = source_payload.get("source_domain")
            candidate_role_raw = source_payload.get("candidate_role")
            relevance_reason_raw = source_payload.get("relevance_reason")
            segment_raw = source_payload.get("segment")
            introduction_raw = source_payload.get("introduction")
            vendor_raw = source_payload.get("vendor")
            official_url = (
                official_url_raw.strip()
                if isinstance(official_url_raw, str) and official_url_raw.strip()
                else None
            )
            source_domain = (
                source_domain_raw.strip()
                if isinstance(source_domain_raw, str) and source_domain_raw.strip()
                else None
            )
            candidate_role = (
                candidate_role_raw.strip()
                if isinstance(candidate_role_raw, str) and candidate_role_raw.strip()
                else None
            )
            relevance_reason = (
                relevance_reason_raw.strip()
                if isinstance(relevance_reason_raw, str) and relevance_reason_raw.strip()
                else None
            )
            segment = (
                segment_raw.strip()
                if isinstance(segment_raw, str) and segment_raw.strip()
                else None
            )
            introduction = (
                introduction_raw.strip()
                if isinstance(introduction_raw, str) and introduction_raw.strip()
                else None
            )
            vendor = (
                vendor_raw.strip()
                if isinstance(vendor_raw, str) and vendor_raw.strip()
                else None
            )
            if (
                official_url is None
                and candidate_role is None
                and relevance_reason is None
                and segment is None
                and introduction is None
                and vendor is None
            ):
                continue
            merged_competitor_sources[competitor_id] = {
                "official_url": official_url,
                "source_domain": source_domain,
                "candidate_role": candidate_role,
                "relevance_reason": relevance_reason,
                "segment": segment,
                "introduction": introduction,
                "vendor": vendor,
            }

    if not new_research_tasks:
        actual_competitors = _research_competitors_from_tasks(list(plan.tasks))
        _assert_research_competitor_subset(
            actual_competitors=actual_competitors,
            allowed_competitors=allowed_competitors,
            context="reconcile.no_new_research",
        )
        actual_competitor_set = set(actual_competitors)
        log.info(
            "planner.reconcile.research_competitor_set",
            cap=max_competitors,
            discovered_count=len(discovered_competitors),
            existing_research_count=len(existing_research),
            new_research_count=0,
            actual_research_count=len(actual_competitors),
            actual_research_competitors=actual_competitors,
            dropped_competitors=[
                competitor
                for competitor in discovered_competitors
                if competitor not in actual_competitor_set
            ],
            capped=len(discovered_competitors) > max_competitors,
        )
        if merged_competitor_sources != dict(plan.competitor_sources):
            return plan.model_copy(
                update={
                    "competitor_sources": merged_competitor_sources,
                    "version": plan.version + 1,
                }
            )
        return plan

    tasks = list(plan.tasks)
    tasks[insert_at:insert_at] = new_research_tasks
    actual_competitors = _research_competitors_from_tasks(tasks)
    _assert_research_competitor_subset(
        actual_competitors=actual_competitors,
        allowed_competitors=allowed_competitors,
        context="reconcile.insert_new_research",
    )
    actual_competitor_set = set(actual_competitors)
    log.info(
        "planner.reconcile.research_competitor_set",
        cap=max_competitors,
        discovered_count=len(discovered_competitors),
        existing_research_count=len(existing_research),
        new_research_count=len(new_research_tasks),
        actual_research_count=len(actual_competitors),
        actual_research_competitors=actual_competitors,
        dropped_competitors=[
            competitor
            for competitor in discovered_competitors
            if competitor not in actual_competitor_set
        ],
        capped=len(discovered_competitors) > max_competitors,
    )
    return plan.model_copy(
        update={
            "tasks": tasks,
            "version": plan.version + 1,
            "competitor_sources": merged_competitor_sources,
        }
    )


async def _persist_planner_step(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    action: str,
    plan: PlanTree,
    llm_response: LLMResponse,
    reasoning_summary: str,
) -> str:
    async with session_factory() as session:
        step = Step(
            step_id=make_id("step_"),
            run_id=run_id,
            agent_name="planner_agent",
            status="running",
            retry_count=0,
            payload={
                "phase": "planning",
                "action": action,
                "task_count": len(plan.tasks),
                "plan_id": plan.plan_id,
                "plan_version": plan.version,
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


def _normalize_user_tasks(additional_tasks: list[PlanTask]) -> list[PlanTask]:
    """Phase β: server-side hardening of `additional_tasks`.

    Rules (each violation drops the offending task — silent skip is *not* used;
    we raise so the FE surfaces the reason instead of producing a partial plan):
    - stage must be in {"research", "analyze", "write"}; "discover" is rejected.
    - research stage requires a non-empty `competitor_id`.
    - title must be non-empty after trim.
    - `task_id` is regenerated (client-supplied IDs are not trusted — would
      collide with planner ptask_ namespace).
    - `source` and `priority` are forced regardless of client payload.
    - `enabled` is forced True (a user-added task that is born disabled is
      contradictory; if they change their mind they can omit it instead).

    Caller enforces the count cap (`MAX_ADDITIONAL_PLAN_TASKS`).
    """
    normalized: list[PlanTask] = []
    for index, task in enumerate(additional_tasks):
        if task.stage not in _USER_ALLOWED_STAGES:
            raise ValueError(
                f"additional_tasks[{index}].stage={task.stage!r} is not user-addable "
                f"(allowed: {sorted(_USER_ALLOWED_STAGES)})"
            )
        title_trimmed = task.title.strip()
        if not title_trimmed:
            raise ValueError(f"additional_tasks[{index}].title must be non-empty")
        competitor_id = task.competitor_id.strip() if task.competitor_id else None
        if task.stage == "research":
            if not competitor_id:
                raise ValueError(
                    f"additional_tasks[{index}].competitor_id is required for stage=research"
                )
        normalized.append(
            PlanTask(
                task_id=make_id("ptask_"),
                stage=task.stage,
                title=title_trimmed[:PLAN_TASK_TITLE_MAX_LEN],
                description=task.description.strip()[:PLAN_TASK_DESCRIPTION_MAX_LEN],
                competitor_id=competitor_id if task.stage == "research" else None,
                focus_dimensions=list(task.focus_dimensions),
                source="user",
                enabled=True,
                priority="user_pinned",
            )
        )
    return normalized


async def _persist_plan_tree_to_run(*, run_id: str, plan: PlanTree) -> None:
    """Mirror the latest plan_tree onto the Run row.

    Same rationale as `_persist_intake_draft_to_run`: lets GET /api/runs/{id}
    render the plan without poking graph state.
    """
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        run.plan_tree = plan.model_dump()
        await session.commit()


async def _persist_intake_draft_to_run(*, run_id: str, draft: RunIntakeDraft) -> None:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        run.intake_draft = draft.model_dump(exclude={"is_complete"})
        await session.commit()


@log_node("planning_profile_wait")
async def planning_profile_wait_node(state: AgentState) -> AgentState:
    raw_reply: Any = interrupt(_build_report_depth_selection_interrupt())
    try:
        reply = IntakeUserReply.model_validate(raw_reply)
    except ValidationError as exc:
        raise RuntimeError(
            f"planning_profile_wait resume value failed validation: {exc}"
        ) from exc

    draft = coerce_intake_draft_or_default(state)
    report_depth = _resolve_report_depth_from_reply(reply)
    updated_draft = draft.model_copy(update={"report_depth": report_depth})

    run_id = state.get("run_id") or make_id("run_")
    await _persist_intake_draft_to_run(run_id=run_id, draft=updated_draft)

    return {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "phase": "planning",
        "intake_draft": updated_draft,
        "report_depth_selection_pending": False,
    }


@log_node("planner_generate")
async def planner_generate_node(state: AgentState) -> AgentState:
    """LLM-driven plan generation. Writes pending_plan_tree + emits plan.published.

    Invariant A: this is the *generate* half. All side effects (LLM call, Step+
    LLMCall persistence, Run.plan_tree mirror, PLAN_PUBLISHED event) commit
    before the wait node's interrupt(). Resumes only re-execute planner_wait.
    """
    session_factory = _resolve_session_factory(state)
    run_id = state.get("run_id") or make_id("run_")
    draft = coerce_intake_draft_or_default(state)
    tier_profile = resolve_tier_profile(draft.report_depth)
    intake_dump = draft.model_dump(exclude={"is_complete"})

    user_prompt = build_planner_user_prompt(
        intake_draft=intake_dump,
        response_language=draft.response_language,
    )
    fallback_user_prompt = build_planner_fallback_user_prompt(
        intake_draft=intake_dump,
        response_language=draft.response_language,
    )
    harness_result = await complete_structured(
        model_slot="research",
        system_prompt=PLANNER_SYSTEM_PROMPT,
        user_prompt=user_prompt,
        output_model=PlannerOutput,
        parser=lambda content: PlannerOutput.parse_llm_content(content, draft=draft),
        fallback_system_prompt=PLANNER_SYSTEM_PROMPT,
        fallback_user_prompt=fallback_user_prompt,
        repair_user_prompt_builder=lambda errors: build_planner_repair_user_prompt(
            validation_errors=errors,
            intake_draft=intake_dump,
        ),
        log_event="planner.harness.finish",
    )
    llm_response = harness_result.llm_response

    if harness_result.value is not None:
        tasks = harness_result.value.to_plan_tasks()
        rationale = harness_result.value.rationale
        action = "publish"
    else:
        tasks = _fallback_tasks(
            draft,
            max_competitors=tier_profile.max_competitors,
            max_dimensions=tier_profile.max_dimensions,
        )
        rationale = ""
        action = "publish_fallback"

    tasks = _cap_plan_tasks_for_profile(
        tasks,
        analysis_archetype=draft.analysis_archetype,
        max_competitors=tier_profile.max_competitors,
        max_dimensions=tier_profile.max_dimensions,
    )

    plan = PlanTree(tasks=tasks, rationale=rationale, version=1, confirmed_at=None)

    step_id = await _persist_planner_step(
        session_factory=session_factory,
        run_id=run_id,
        action=action,
        plan=plan,
        llm_response=llm_response,
        reasoning_summary=rationale,
    )
    await _persist_plan_tree_to_run(run_id=run_id, plan=plan)

    with bind_step(step_id):
        log.info(
            "planner.publish",
            run_id=run_id,
            task_count=len(plan.tasks),
            action=action,
            llm_provider=llm_response.provider,
            llm_fallback_used=llm_response.fallback_used,
        )

    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.PLAN_PUBLISHED,
        step_id=step_id,
        payload={
            "plan_id": plan.plan_id,
            "task_count": len(plan.tasks),
            "version": plan.version,
            "plan_tree": plan.model_dump(),
        },
    )

    # Seed state.competitors from the intake's explicit list so the executor
    # cannot drop user-named targets when discovery also adds candidates.
    # operator.add appends, so return only the diff.
    existing_competitors = list(state.get("competitors") or [])
    competitors_seed: list[str] = []
    if draft.competitors_explicit:
        seen = set(existing_competitors)
        for competitor in draft.competitors_explicit:
            if competitor in seen:
                continue
            seen.add(competitor)
            competitors_seed.append(competitor)

    result: dict[str, Any] = {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "phase": "planning",
        "pending_plan_tree": plan,
    }
    if competitors_seed:
        result["competitors"] = competitors_seed
    return result


@log_node("planner_wait")
async def planner_wait_node(state: AgentState) -> AgentState:
    """Pure interrupt node. Idempotent: on replay it just re-issues interrupt().

    Invariant A: no LLM calls, no DB writes before interrupt(). All side effects
    after interrupt() run exactly once per resume.

    Phase β: honors `disabled_task_ids` against pending plan tasks AND merges
    `additional_tasks` (forced source="user", priority="user_pinned") onto the
    end of the kept list. User-pinned research competitors that aren't yet in
    `state.competitors` are returned as a diff so the supervisor can skip the
    discovery round-trip and target them directly.
    """
    pending = _coerce_pending_plan(state)
    draft = coerce_intake_draft_or_default(state)
    tier_profile = resolve_tier_profile(draft.report_depth)
    raw_confirm: Any = interrupt(
        {"kind": "plan_confirm", "plan_tree": pending.model_dump()}
    )

    try:
        confirm = PlanConfirmRequest.model_validate(raw_confirm)
    except ValidationError as exc:
        # Same fail-fast contract as intake_wait: the resume endpoint is the
        # sole writer of resume values and must validate before Command(resume=).
        raise RuntimeError(
            f"planner_wait resume value failed validation: {exc}"
        ) from exc

    if len(confirm.additional_tasks) > MAX_ADDITIONAL_PLAN_TASKS:
        raise RuntimeError(
            f"additional_tasks count ({len(confirm.additional_tasks)}) "
            f"exceeds limit ({MAX_ADDITIONAL_PLAN_TASKS})"
        )
    try:
        user_tasks = _normalize_user_tasks(confirm.additional_tasks)
    except ValueError as exc:
        raise RuntimeError(f"additional_tasks validation failed: {exc}") from exc

    disabled = set(confirm.disabled_task_ids)
    pending_task_ids = {task.task_id for task in pending.tasks}
    unknown_disabled = [tid for tid in disabled if tid not in pending_task_ids]
    if unknown_disabled:
        # FE may race against a stale plan version. Surface the mismatch
        # instead of silently dropping the unknown IDs.
        raise RuntimeError(
            f"disabled_task_ids reference non-existent tasks: {sorted(unknown_disabled)}"
        )

    kept_tasks = [task for task in pending.tasks if task.task_id not in disabled]
    merged_candidates = _merge_plan_tasks_with_user_priority(
        kept_tasks=kept_tasks,
        user_tasks=user_tasks,
    )
    merged_tasks = _cap_plan_tasks_for_profile(
        merged_candidates,
        analysis_archetype=draft.analysis_archetype,
        max_competitors=tier_profile.max_competitors,
        max_dimensions=tier_profile.max_dimensions,
    )
    confirmed = PlanTree(
        plan_id=pending.plan_id,
        tasks=merged_tasks,
        rationale=pending.rationale,
        version=pending.version + 1,
        confirmed_at=datetime.now(timezone.utc).isoformat(),
    )

    run_id = state.get("run_id") or make_id("run_")
    await _persist_plan_tree_to_run(run_id=run_id, plan=confirmed)

    # User-injected research competitors must be added to state.competitors so
    # the supervisor's hard-constraint guard accepts them. operator.add
    # concatenates onto current state — we only return the *diff* (new IDs)
    # to avoid duplicates.
    existing_competitors = set(state.get("competitors", []) or [])
    new_user_competitors = [
        task.competitor_id
        for task in confirmed.tasks
        if task.stage == "research"
        and task.source == "user"
        and task.competitor_id is not None
        and task.competitor_id not in existing_competitors
    ]
    # De-dup the diff itself (user could have added the same competitor twice).
    seen_diff: set[str] = set()
    competitors_diff: list[str] = []
    for competitor in new_user_competitors:
        if competitor in seen_diff:
            continue
        seen_diff.add(competitor)
        competitors_diff.append(competitor)

    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.PLAN_CONFIRMED,
        step_id=None,
        payload={
            "plan_id": confirmed.plan_id,
            "version": confirmed.version,
            "kept_task_count": len(kept_tasks),
            "user_task_count": len(user_tasks),
            "disabled_task_ids": sorted(disabled),
            "confirmed_at": confirmed.confirmed_at,
        },
    )

    # `**state` would spread operator.add fields from the current snapshot, but
    # LangGraph would treat those full lists as deltas and append them again.
    # Drop accumulating fields from the spread; append only the true diff below.
    result: dict[str, Any] = {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "phase": "executing",
        "plan_tree": confirmed,
        "pending_plan_tree": None,
    }
    if competitors_diff:
        result["competitors"] = competitors_diff
    return result
