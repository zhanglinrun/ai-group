from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from agents.state import AgentState, spread_without_accumulators
from core.defaults import (
    DEFAULT_FOCUS_DIMENSIONS,
    DEFAULT_DISCOVER_MAX_RESULTS,
    MAX_FOCUS_DIMENSIONS,
    MAX_QA_RERESEARCH_ITERATIONS,
    MAX_WRITE_SECTIONS,
)
from core.tiers import TierProfile, resolve_tier_profile
from db.engine import get_session_factory
from models.run import Run
from models.step import Step
from models.supervisor_decision import SupervisorDecisionRecord
from service.llm import (
    SUPERVISOR_SYSTEM_PROMPT,
    build_supervisor_fallback_user_prompt,
    build_supervisor_repair_user_prompt,
    build_supervisor_user_prompt,
)
from service.llm.harness import complete_structured
from service.llm.records import build_llm_call_record
from service.llm.response import LLMResponse
from service.locale import detect_language
from service.run_status_reason import build_degraded_reason
from utils.log_node import log_node
from utils.logger import bind_step, get_logger

log = get_logger("agents.supervisor")
from schemas.agent_outputs import SupervisorToolCallOutput
from schemas.contracts import (
    COMPARISON_SCHEMA_BASE_DIMENSIONS,
    ensure_comparison_schema_dimensions,
    normalize_dimensions,
    research_focus_dimensions,
)
from schemas.ids import make_id
from schemas.supervisor import (
    Analyze,
    ConductResearch,
    ConductResearchBatch,
    DiscoverCompetitors,
    Finalize,
    SupervisorDecision,
    Write,
)
from service.event_bus import RunEventType, emit_run_event

DIMENSION_HINTS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("pricing", ("pricing", "price", "cost", "套餐", "定价", "收费")),
    ("user_feedback", ("review", "feedback", "rating", "评价", "口碑", "用户声音")),
    ("feature", ("feature", "capability", "功能", "能力", "workflow")),
    ("positioning", ("positioning", "market", "segment", "定位", "市场")),
    ("tech_stack", ("integration", "api", "architecture", "tech", "技术", "集成")),
    ("go_to_market", ("growth", "distribution", "channel", "营销", "获客")),
)
TriggerSource = Literal[
    "user_query",
    "researcher_completion",
    "analyst_completion",
    "writer_completion",
    "qa_approval",
    "qa_rejection",
    "iteration_advance",
]
FocusDimensionSource = Literal["upstream_task", "intake", "hints", "default", "llm_tool_output"]
PlanTaskStage = Literal["discover", "research", "analyze", "write"]
LandscapeTopicSignature = tuple[str, tuple[str, ...], int, int, bool]
_DIMENSIONAL_SUPERVISOR_TOOLS = frozenset(
    {"ConductResearch", "ConductResearchBatch", "Analyze", "Write"}
)
_LANDSCAPE_CORE_ROLES = frozenset(
    {"direct_competitor", "adjacent_competitor", "substitute"}
)


def _resolve_triggered_by(
    *,
    iteration: int,
    last_completed_node: Literal["researcher", "analyst", "writer"] | None,
    qa_outcome: Literal["approved", "rejected", "force_degraded"] | None,
) -> TriggerSource:
    if qa_outcome == "approved":
        return "qa_approval"
    if qa_outcome in {"rejected", "force_degraded"}:
        return "qa_rejection"
    if iteration == 1:
        return "user_query"
    if last_completed_node == "researcher":
        return "researcher_completion"
    if last_completed_node == "analyst":
        return "analyst_completion"
    if last_completed_node == "writer":
        return "writer_completion"
    return "iteration_advance"


def _stable_unique(values: list[str]) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        ordered.append(value)
    return ordered


def _stable_numeric_claims(values: list[dict[str, object]]) -> list[dict[str, object]]:
    ordered: list[dict[str, object]] = []
    seen: set[tuple[str, str]] = set()
    for value in values:
        claim = value.get("claim")
        section_id = value.get("section_id")
        key = (
            " ".join(claim.split()).casefold() if isinstance(claim, str) else "",
            section_id.strip().casefold() if isinstance(section_id, str) else "",
        )
        if key == ("", "") or key in seen:
            continue
        seen.add(key)
        ordered.append(value)
    return ordered


def _clean_optional_string(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip()
    return cleaned or None


def _get_object_field(item: object, field_name: str) -> object:
    if isinstance(item, dict):
        return item.get(field_name)
    return getattr(item, field_name, None)


def _state_or_intake_string(state: AgentState, field_name: str) -> str | None:
    direct = _clean_optional_string(state.get(field_name))
    if direct is not None:
        return direct
    intake_draft = state.get("intake_draft")
    if intake_draft is None:
        return None
    return _clean_optional_string(_get_object_field(intake_draft, field_name))


def _state_response_language(state: AgentState, *, user_query: str) -> str:
    value = _state_or_intake_string(state, "response_language")
    if value in {"zh", "en"}:
        return value
    return detect_language(user_query)


def _discovery_search_queries(
    *,
    user_query: str,
    domain_context: str | None,
    market_scope: str | None,
    response_language: str,
) -> list[str]:
    query_basis = domain_context.strip() if isinstance(domain_context, str) and domain_context.strip() else user_query
    scope_prefix = f"{market_scope} " if market_scope else ""
    combined_context = f"{user_query} {query_basis}".casefold()
    lowered_basis = query_basis.casefold()
    is_broad_market_query = any(
        term in combined_context
        for term in ("全景", "趋势", "市场", "赛道", "行业", "landscape", "market", "trend")
    )
    is_broad_ai_hardware = (
        ("ai硬件" in combined_context or "ai hardware" in combined_context)
        and "眼镜" not in combined_context
        and "glasses" not in combined_context
    )
    if response_language == "zh":
        if is_broad_market_query:
            candidates = [
                f"{scope_prefix}{query_basis} 细分赛道 产品类型 应用场景 代表产品",
                f"{scope_prefix}{query_basis} 主流产品 新品 发布 厂商",
                f"{scope_prefix}{query_basis} 市场格局 代表产品 厂商 终端设备",
                f"{scope_prefix}{query_basis} 趋势 报告 产品类型 应用场景",
            ]
            if is_broad_ai_hardware:
                candidates.append(
                    f"{scope_prefix}{query_basis} AI眼镜 AI录音笔 AI玩具 AI PC 机器人 家庭AI终端 可穿戴"
                )
        else:
            candidates = [
                f"{scope_prefix}{query_basis} 竞品 替代 产品",
                f"{scope_prefix}{query_basis} 对比 评测 厂商",
                f"{scope_prefix}{query_basis} 市场 解决方案",
            ]
    else:
        if is_broad_market_query:
            candidates = [
                f"{scope_prefix}{query_basis} product segments product types use cases representative products",
                f"{scope_prefix}{query_basis} mainstream products new launches vendors",
                f"{scope_prefix}{query_basis} market landscape representative products vendors edge devices",
                f"{scope_prefix}{query_basis} trends report product types applications",
            ]
            if is_broad_ai_hardware:
                candidates.append(
                    f"{scope_prefix}{query_basis} smart glasses AI recorder AI toys AI PC robots home AI terminal wearable"
                )
        else:
            candidates = [
                f"{scope_prefix}{query_basis} competitors alternatives",
                f"{scope_prefix}{query_basis} comparison reviews vendors",
                f"{scope_prefix}{query_basis} market solutions",
            ]
    return _stable_unique([item.strip() for item in candidates if item.strip()])[:5]


def _normalize_focus_dimensions(raw: object, *, max_dimensions: int) -> list[str]:
    if not isinstance(raw, list):
        return []
    return normalize_dimensions(
        [
        item.strip()
        for item in raw
        if isinstance(item, str) and item.strip()
        ],
        allow_empty=True,
    )[:max_dimensions]


def _derive_hint_focus_dimensions(
    *,
    user_query: str,
    competitors: list[str],
    max_dimensions: int,
) -> list[str]:
    normalized_query = user_query.lower()
    derived: list[str] = []
    for dimension, hints in DIMENSION_HINTS:
        if any(hint in normalized_query for hint in hints):
            derived.append(dimension)

    if len(competitors) >= 3 and "positioning" not in derived:
        derived.append("positioning")
    if not derived:
        return []
    if len(derived) < 3:
        derived.extend(DEFAULT_FOCUS_DIMENSIONS)
    return _stable_unique(derived)[:max_dimensions]


def _derive_focus_dimensions(
    *,
    user_query: str,
    competitors: list[str],
    max_dimensions: int,
) -> list[str]:
    hint_dimensions = _derive_hint_focus_dimensions(
        user_query=user_query,
        competitors=competitors,
        max_dimensions=max_dimensions,
    )
    if hint_dimensions:
        return hint_dimensions
    return list(DEFAULT_FOCUS_DIMENSIONS)[:max_dimensions]


def _current_plan_stage(
    *,
    competitors: list[str],
    researched_competitors: list[str],
    analysis_done: bool,
    report_draft_done: bool,
    qa_reject_to: Literal["researcher", "analyst", "writer", "supervisor"] | None,
) -> PlanTaskStage:
    if qa_reject_to == "researcher":
        return "research"
    if qa_reject_to == "analyst":
        return "analyze"
    if qa_reject_to == "writer":
        return "write"
    if not competitors:
        return "discover"
    pending_competitors = [c for c in competitors if c not in researched_competitors]
    if pending_competitors:
        return "research"
    if not analysis_done:
        return "analyze"
    if not report_draft_done:
        return "write"
    return "write"


def _plan_task_focus_dimensions(
    *,
    plan_tree: object,
    stage: PlanTaskStage,
    target_competitors: list[str],
    max_dimensions: int,
) -> list[str]:
    tasks_raw = _get_object_field(plan_tree, "tasks")
    if not isinstance(tasks_raw, list):
        return []

    target_competitor_ids = set(target_competitors)
    matched: list[str] = []
    stage_fallback: list[str] = []
    for task in tasks_raw:
        if _get_object_field(task, "enabled") is False:
            continue
        if _get_object_field(task, "stage") != stage:
            continue
        dimensions = _normalize_focus_dimensions(
            _get_object_field(task, "focus_dimensions"),
            max_dimensions=max_dimensions,
        )
        if not dimensions:
            continue
        competitor_id_raw = _get_object_field(task, "competitor_id")
        competitor_id = (
            competitor_id_raw.strip()
            if isinstance(competitor_id_raw, str)
            else ""
        )
        if stage == "research" and target_competitor_ids:
            if competitor_id in target_competitor_ids:
                matched.extend(dimensions)
            else:
                stage_fallback.extend(dimensions)
        else:
            matched.extend(dimensions)

    return _stable_unique(matched or stage_fallback)[:max_dimensions]


def _intake_focus_dimensions(intake_draft: object, *, max_dimensions: int) -> list[str]:
    return _normalize_focus_dimensions(
        _get_object_field(intake_draft, "focus_dimensions"),
        max_dimensions=max_dimensions,
    )


def _analysis_archetype_from_intake(intake_draft: object) -> str:
    raw = _get_object_field(intake_draft, "analysis_archetype")
    return "landscape" if raw == "landscape" else "comparison"


def _resolve_fallback_dimensions(
    *,
    plan_tree: object,
    intake_draft: object,
    user_query: str,
    competitors: list[str],
    researched_competitors: list[str],
    analysis_done: bool,
    report_draft_done: bool,
    qa_reject_to: Literal["researcher", "analyst", "writer", "supervisor"] | None = None,
    max_dimensions: int = MAX_FOCUS_DIMENSIONS,
) -> tuple[list[str], FocusDimensionSource]:
    stage = _current_plan_stage(
        competitors=competitors,
        researched_competitors=researched_competitors,
        analysis_done=analysis_done,
        report_draft_done=report_draft_done,
        qa_reject_to=qa_reject_to,
    )
    pending_competitors = [c for c in competitors if c not in researched_competitors]
    target_competitors = pending_competitors if stage == "research" else []
    if qa_reject_to == "researcher":
        target_competitors = competitors
    analysis_archetype = _analysis_archetype_from_intake(intake_draft)

    plan_dimensions = _plan_task_focus_dimensions(
        plan_tree=plan_tree,
        stage=stage,
        target_competitors=target_competitors,
        max_dimensions=max_dimensions,
    )
    if plan_dimensions:
        if stage == "research":
            return (
                research_focus_dimensions(
                    plan_dimensions,
                    analysis_archetype=analysis_archetype,
                ),
                "upstream_task",
            )
        return plan_dimensions, "upstream_task"

    intake_dimensions = _intake_focus_dimensions(intake_draft, max_dimensions=max_dimensions)
    if intake_dimensions:
        if stage == "research":
            return (
                research_focus_dimensions(
                    intake_dimensions,
                    analysis_archetype=analysis_archetype,
                ),
                "intake",
            )
        return intake_dimensions, "intake"

    hint_dimensions = _derive_hint_focus_dimensions(
        user_query=user_query,
        competitors=competitors,
        max_dimensions=max_dimensions,
    )
    if hint_dimensions:
        if stage == "research":
            return (
                research_focus_dimensions(
                    hint_dimensions,
                    analysis_archetype=analysis_archetype,
                ),
                "hints",
            )
        return hint_dimensions, "hints"

    default_dimensions = list(DEFAULT_FOCUS_DIMENSIONS)[:max_dimensions]
    if stage == "research":
        default_dimensions = research_focus_dimensions(
            default_dimensions,
            analysis_archetype=analysis_archetype,
        )
    else:
        default_dimensions = ensure_comparison_schema_dimensions(
            default_dimensions,
            analysis_archetype=analysis_archetype,
        )
    return default_dimensions, "default"


def _derive_write_sections(*, focus_dimensions: list[str]) -> list[str]:
    sections = _stable_unique([*focus_dimensions, "differentiation"])
    return sections[:MAX_WRITE_SECTIONS]


def _has_pending_research(
    *,
    competitors: list[str],
    researched_competitors: list[str],
) -> bool:
    researched = set(researched_competitors)
    return any(competitor not in researched for competitor in competitors)


def _qa_requests_analyst_retry(
    *,
    qa_outcome: Literal["approved", "rejected", "force_degraded"] | None,
    qa_reject_to: Literal["researcher", "analyst", "writer", "supervisor"] | None,
) -> bool:
    return qa_outcome == "rejected" and qa_reject_to == "analyst"


def _should_route_write_after_analysis(
    *,
    competitors: list[str],
    researched_competitors: list[str],
    prior_decisions: list[SupervisorDecision],
    analysis_done: bool,
    report_draft_done: bool,
    qa_outcome: Literal["approved", "rejected", "force_degraded"] | None,
    qa_reject_to: Literal["researcher", "analyst", "writer", "supervisor"] | None,
) -> bool:
    if not analysis_done or report_draft_done:
        return False
    if _qa_requests_analyst_retry(qa_outcome=qa_outcome, qa_reject_to=qa_reject_to):
        return False
    if qa_outcome == "rejected" and qa_reject_to == "researcher":
        return False
    if any(decision.chosen_tool == "Write" for decision in prior_decisions):
        return False
    return not _has_pending_research(
        competitors=competitors,
        researched_competitors=researched_competitors,
    )


def _write_after_analysis_decision(
    *,
    run_id: str,
    iteration: int,
    triggered_by: TriggerSource,
    fallback_sections: list[str],
    reason: str,
) -> SupervisorDecision:
    now = _now_iso()
    return SupervisorDecision(
        id=make_id("decision_"),
        run_id=run_id,
        iteration=iteration,
        chosen_tool="Write",
        tool_args=Write(
            template_id=None,
            sections=fallback_sections,
        ).model_dump(),
        reasoning_summary=reason,
        triggered_by=triggered_by,
        outcome="dispatched",
        outcome_recorded_at=now,
        created_at=now,
    )


async def _load_prior_writer_contract(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    target_step_id: str | None,
) -> tuple[str | None, list[str]]:
    async with session_factory() as session:
        writer_step: Step | None = None
        if target_step_id:
            candidate = await session.get(Step, target_step_id)
            if (
                candidate is not None
                and candidate.run_id == run_id
                and candidate.agent_name == "writer"
                and isinstance(candidate.payload, dict)
            ):
                writer_step = candidate
        if writer_step is None:
            writer_step = (
                await session.execute(
                    select(Step)
                    .where(Step.run_id == run_id, Step.agent_name == "writer")
                    .order_by(Step.created_at.asc())
                    .limit(1)
                )
            ).scalars().first()
        if writer_step is None or not isinstance(writer_step.payload, dict):
            return None, []

        template_raw = writer_step.payload.get("template_id")
        template_id = template_raw if isinstance(template_raw, str) and template_raw else None
        sections_raw = writer_step.payload.get("target_sections")
        if not isinstance(sections_raw, list):
            sections_raw = writer_step.payload.get("sections")
        sections = (
            [item for item in sections_raw if isinstance(item, str) and item]
            if isinstance(sections_raw, list)
            else []
        )
        return template_id, _stable_unique(sections)[:MAX_WRITE_SECTIONS]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _resolve_session_factory(state: AgentState) -> async_sessionmaker[AsyncSession]:
    return get_session_factory()


def _pseudo_llm_response(
    *,
    provider: str,
    model_name: str,
    prompt_preview: str,
    error: str | None,
) -> LLMResponse:
    return LLMResponse(
        model_slot="research",
        provider=provider,
        model_name=model_name,
        prompt_preview=prompt_preview,
        prompt_hash="pseudo_response",
        content={},
        prompt_tokens=None,
        completion_tokens=None,
        latency_ms=0,
        error=error,
    )


def _fallback_decision(
    *,
    run_id: str,
    iteration: int,
    competitors: list[str],
    researched_competitors: list[str],
    analysis_done: bool,
    report_draft_done: bool,
    triggered_by: TriggerSource,
    user_query: str,
    fallback_dimensions: list[str],
    fallback_sections: list[str],
    profile: TierProfile | None = None,
    market_scope: str | None = None,
    domain_context: str | None = None,
    response_language: str = "en",
) -> SupervisorDecision:
    effective_profile = profile or resolve_tier_profile(None)
    now = _now_iso()

    if not competitors:
        args = DiscoverCompetitors(
            search_queries=_discovery_search_queries(
                user_query=user_query,
                domain_context=domain_context,
                market_scope=market_scope,
                response_language=response_language,
            ),
            domain_context=domain_context or user_query,
            max_results=DEFAULT_DISCOVER_MAX_RESULTS,
        ).model_dump()
        return SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="DiscoverCompetitors",
            tool_args=args,
            reasoning_summary="No competitors provided; fallback triggers discovery phase.",
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )

    pending_competitors = [c for c in competitors if c not in researched_competitors]
    now = _now_iso()

    if len(pending_competitors) >= 2:
        topics = [
            ConductResearch(
                research_topic=f"{competitor_id} vs user_query={user_query}",
                competitor_id=competitor_id,
                focus_dimensions=fallback_dimensions,
                max_iterations=effective_profile.react_turns,
                search_max_results=effective_profile.search_max_results,
                fallback_to_offline=True,
            )
            for competitor_id in pending_competitors[:effective_profile.max_competitors]
        ]
        args = ConductResearchBatch(
            topics=topics,
            parallelism_rationale=(
                f"Fallback planner batches {len(topics)} pending competitors to reduce wall-clock time."
            ),
        ).model_dump()
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="ConductResearchBatch",
            tool_args=args,
            reasoning_summary=(
                f"Fallback planner dispatches {len(topics)} pending competitors in parallel research."
            ),
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return decision

    if len(pending_competitors) == 1:
        competitor_id = pending_competitors[0]
        args = ConductResearch(
            research_topic=f"{competitor_id} vs user_query={user_query}",
            competitor_id=competitor_id,
            focus_dimensions=fallback_dimensions,
            max_iterations=effective_profile.react_turns,
            search_max_results=effective_profile.search_max_results,
            fallback_to_offline=True,
        ).model_dump()
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="ConductResearch",
            tool_args=args,
            reasoning_summary=f"Fallback planner selects pending competitor `{competitor_id}`.",
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return decision

    if not analysis_done:
        args = Analyze(
            focus_dimensions=fallback_dimensions,
            parallel_by_dimension=False,
            require_cross_competitor=True,
        ).model_dump()
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="Analyze",
            tool_args=args,
            reasoning_summary="Fallback planner moves to cross-competitor analysis.",
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return decision

    if not report_draft_done:
        args = Write(
            template_id=None,
            sections=fallback_sections,
        ).model_dump()
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="Write",
            tool_args=args,
            reasoning_summary="Fallback planner composes report draft after analysis.",
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return decision

    args = Finalize(
        completion_reason="all_dimensions_covered",
        notes="All planned phases completed.",
    ).model_dump()
    decision = SupervisorDecision(
        id=make_id("decision_"),
        run_id=run_id,
        iteration=iteration,
        chosen_tool="Finalize",
        tool_args=args,
        reasoning_summary="Fallback planner finalizes after research/analysis/write phases.",
        triggered_by=triggered_by,
        outcome="succeeded",
        outcome_recorded_at=now,
        created_at=now,
    )
    return decision


async def _decision_from_qa_feedback(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    iteration: int,
    triggered_by: TriggerSource,
    qa_outcome: Literal["approved", "rejected", "force_degraded"] | None,
    qa_reject_to: Literal["researcher", "analyst", "writer", "supervisor"] | None,
    qa_reasons: list[str],
    qa_degrade_reason: str | None,
    qa_degraded_required_sections: list[str],
    qa_unsupported_numeric_claims: list[dict[str, object]],
    user_query: str,
    competitors: list[str],
    fallback_dimensions: list[str],
    fallback_sections: list[str],
    pending_review_target_step_id: str | None,
    profile: TierProfile | None = None,
) -> tuple[SupervisorDecision, LLMResponse, bool] | None:
    effective_profile = profile or resolve_tier_profile(None)
    if qa_outcome is None or qa_outcome == "approved":
        return None

    now = _now_iso()
    if qa_outcome == "force_degraded":
        if qa_degrade_reason == "report_degraded_required_sections":
            degraded_sections = ", ".join(qa_degraded_required_sections) or "unknown"
            note = (
                "Writer reported required sections with insufficient grounded evidence; "
                f"finalize in degraded mode (degraded_required={degraded_sections})."
            )
            prompt_preview = "qa_data_degraded"
            error_code = "qa_data_degraded"
        else:
            note = "QA max retries hit; force finalize in degraded mode."
            prompt_preview = "qa_force_degraded"
            error_code = "qa_force_degraded"
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="Finalize",
            tool_args=Finalize(
                completion_reason="fallback_path",
                notes=note,
            ).model_dump(),
            reasoning_summary=note,
            triggered_by=triggered_by,
            outcome="succeeded",
            outcome_recorded_at=now,
            created_at=now,
        )
        return (
            decision,
            _pseudo_llm_response(
                provider="qa_guardrail",
                model_name="qa_guardrail",
                prompt_preview=prompt_preview,
                error=error_code,
            ),
            True,
        )

    if qa_reject_to == "supervisor":
        # Let the planner decide next action with full context instead of forcing degraded finalize.
        return None

    if qa_reject_to == "writer":
        qa_reason_summary = "; ".join(qa_reasons[:3]) or "QA blocking rules failed."
        prior_template_id, prior_sections = await _load_prior_writer_contract(
            session_factory=session_factory,
            run_id=run_id,
            target_step_id=pending_review_target_step_id,
        )
        if prior_sections:
            sections_source = "prior_writer_step"
            rewrite_sections = list(prior_sections)
        else:
            sections_source = "fallback"
            rewrite_sections = list(fallback_sections)
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="Write",
            tool_args=Write(
                template_id=prior_template_id,
                sections=rewrite_sections,
                qa_reasons=qa_reasons,
                unsupported_numeric_claims=qa_unsupported_numeric_claims,
            ).model_dump(),
            reasoning_summary=(
                "QA rejected writer output and requests rewrite: "
                f"{qa_reason_summary} "
                f"(rewrite_sections_source={sections_source}, sections={len(rewrite_sections)})"
            ),
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return (
            decision,
            _pseudo_llm_response(
                provider="qa_guardrail",
                model_name="qa_guardrail",
                prompt_preview="qa_rejected_to_writer",
                error=None,
            ),
            False,
        )

    if qa_reject_to == "analyst":
        qa_reason_summary = "; ".join(qa_reasons[:3]) or "QA requests deeper analysis."
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="Analyze",
            tool_args=Analyze(
                focus_dimensions=fallback_dimensions,
                parallel_by_dimension=True,
                require_cross_competitor=True,
            ).model_dump(),
            reasoning_summary=(
                "QA rejected current report and requests analyst re-check: "
                f"{qa_reason_summary}"
            ),
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return (
            decision,
            _pseudo_llm_response(
                provider="qa_guardrail",
                model_name="qa_guardrail",
                prompt_preview="qa_rejected_to_analyst",
                error=None,
            ),
            False,
        )

    if qa_reject_to == "researcher":
        qa_reason_summary = "; ".join(qa_reasons[:3]) or "QA requests additional evidence."
        topics = [
            ConductResearch(
                research_topic=(
                    "Collect additional evidence to address QA findings for "
                    f"{competitor_id} on query: {user_query}"
                ),
                competitor_id=competitor_id,
                focus_dimensions=fallback_dimensions,
                max_iterations=min(MAX_QA_RERESEARCH_ITERATIONS, effective_profile.react_turns),
                search_max_results=effective_profile.search_max_results,
                fallback_to_offline=True,
            )
            for competitor_id in competitors[:effective_profile.max_competitors]
        ]
        if not topics:
            return None
        if len(topics) == 1:
            chosen_tool: Literal["ConductResearch", "ConductResearchBatch"] = "ConductResearch"
            tool_args = topics[0].model_dump()
        else:
            chosen_tool = "ConductResearchBatch"
            tool_args = ConductResearchBatch(
                topics=topics,
                parallelism_rationale=(
                    "QA requested additional evidence, rerun research across competitors in parallel."
                ),
            ).model_dump()
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool=chosen_tool,
            tool_args=tool_args,
            reasoning_summary=f"QA requires additional research evidence: {qa_reason_summary}",
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        return (
            decision,
            _pseudo_llm_response(
                provider="qa_guardrail",
                model_name="qa_guardrail",
                prompt_preview="qa_rejected_to_researcher",
                error=None,
            ),
            False,
        )

    note = (
        f"QA rejected output to `{qa_reject_to}` but this path is not implemented in fast-path slice; "
        "fallback to finalize degraded."
    )
    decision = SupervisorDecision(
        id=make_id("decision_"),
        run_id=run_id,
        iteration=iteration,
        chosen_tool="Finalize",
        tool_args=Finalize(
            completion_reason="fallback_path",
            notes=note,
        ).model_dump(),
        reasoning_summary=note,
        triggered_by=triggered_by,
        outcome="succeeded",
        outcome_recorded_at=now,
        created_at=now,
    )
    return (
        decision,
        _pseudo_llm_response(
            provider="qa_guardrail",
            model_name="qa_guardrail",
            prompt_preview="qa_rejected_unimplemented_target",
            error="qa_rejected_unimplemented_target",
        ),
        True,
    )


def _decision_from_tool_output(
    *,
    run_id: str,
    iteration: int,
    output: SupervisorToolCallOutput,
    triggered_by: TriggerSource,
    fallback_dimensions: list[str],
    fallback_sections: list[str],
    profile: TierProfile | None = None,
) -> SupervisorDecision:
    effective_profile = profile or resolve_tier_profile(None)
    now = _now_iso()
    outcome: Literal["dispatched", "succeeded"] = (
        "succeeded" if output.chosen_tool == "Finalize" else "dispatched"
    )
    tool_args = _clamp_tool_args_to_canonical_dimensions(
        chosen_tool=output.chosen_tool,
        tool_args=output.tool_args,
        fallback_dimensions=fallback_dimensions,
        fallback_sections=fallback_sections,
        profile=effective_profile,
    )
    return SupervisorDecision(
        id=make_id("decision_"),
        run_id=run_id,
        iteration=iteration,
        chosen_tool=output.chosen_tool,
        tool_args=tool_args,
        reasoning_summary=output.reasoning_summary,
        triggered_by=triggered_by,
        outcome=outcome,
        outcome_recorded_at=now,
        created_at=now,
    )


def _clamp_tool_args_to_canonical_dimensions(
    *,
    chosen_tool: str,
    tool_args: dict[str, object],
    fallback_dimensions: list[str],
    fallback_sections: list[str],
    profile: TierProfile | None = None,
) -> dict[str, object]:
    effective_profile = profile or resolve_tier_profile(None)
    if chosen_tool not in _DIMENSIONAL_SUPERVISOR_TOOLS:
        return tool_args
    canonical_dimensions = normalize_dimensions(fallback_dimensions, allow_empty=True)
    if not canonical_dimensions:
        canonical_dimensions = list(DEFAULT_FOCUS_DIMENSIONS)[:effective_profile.max_dimensions]
    canonical_cap = effective_profile.max_dimensions
    if all(dimension in canonical_dimensions for dimension in COMPARISON_SCHEMA_BASE_DIMENSIONS):
        canonical_cap = max(canonical_cap, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
    canonical_dimensions = canonical_dimensions[:canonical_cap]

    def clamp_dimensions(value: object) -> list[str]:
        candidate: list[str] = []
        if isinstance(value, list):
            candidate = normalize_dimensions(
                [item for item in value if isinstance(item, str)],
                allow_empty=True,
            )
        dimensions = candidate or list(canonical_dimensions)
        cap = effective_profile.max_dimensions
        if all(dimension in dimensions for dimension in COMPARISON_SCHEMA_BASE_DIMENSIONS):
            cap = max(cap, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
        return dimensions[:cap]

    def clamp_react_turns(value: object) -> int:
        if isinstance(value, int) and value > 0:
            return min(value, effective_profile.react_turns)
        return effective_profile.react_turns

    def clamp_search_max_results(value: object) -> int:
        if isinstance(value, int) and value > 0:
            return min(value, effective_profile.search_max_results)
        return effective_profile.search_max_results

    clamped = dict(tool_args)
    if chosen_tool == "ConductResearch":
        clamped["focus_dimensions"] = clamp_dimensions(clamped.get("focus_dimensions"))
        clamped["max_iterations"] = clamp_react_turns(clamped.get("max_iterations"))
        clamped["search_max_results"] = clamp_search_max_results(
            clamped.get("search_max_results")
        )
    elif chosen_tool == "ConductResearchBatch":
        topics = clamped.get("topics")
        if isinstance(topics, list):
            capped_topics: list[dict[str, object]] = []
            for topic in topics:
                if not isinstance(topic, dict):
                    continue
                capped_topics.append(
                    {
                        **topic,
                        "focus_dimensions": clamp_dimensions(topic.get("focus_dimensions")),
                        "max_iterations": clamp_react_turns(topic.get("max_iterations")),
                        "search_max_results": clamp_search_max_results(
                            topic.get("search_max_results")
                        ),
                    }
                )
            clamped["topics"] = capped_topics[:effective_profile.max_competitors]
    elif chosen_tool == "Analyze":
        clamped["focus_dimensions"] = canonical_dimensions
    elif chosen_tool == "Write":
        clamped["sections"] = _stable_unique(fallback_sections or _derive_write_sections(
            focus_dimensions=canonical_dimensions
        ))[:MAX_WRITE_SECTIONS]
    return clamped


async def _persist_iteration(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    iteration: int,
    decision: SupervisorDecision,
    llm_response: LLMResponse,
) -> str:
    async with session_factory() as session:
        step = Step(
            step_id=make_id("step_"),
            run_id=run_id,
            agent_name="supervisor",
            status="running",
            retry_count=0,
            payload={
                "iteration": iteration,
                "chosen_tool": decision.chosen_tool,
                "tool_args": decision.tool_args,
                "llm_provider": llm_response.provider,
                "llm_prompt_preview": llm_response.prompt_preview,
                "llm_fallback_used": llm_response.fallback_used,
                "llm_fallback_reason": llm_response.fallback_reason,
            },
        )
        session.add(step)
        await session.flush()
        session.add(build_llm_call_record(step_id=step.step_id, response=llm_response))
        session.add(
            SupervisorDecisionRecord(
                id=decision.id,
                run_id=decision.run_id,
                iteration=decision.iteration,
                chosen_tool=decision.chosen_tool,
                tool_args=decision.tool_args,
                reasoning_summary=decision.reasoning_summary,
                triggered_by=decision.triggered_by,
                outcome=decision.outcome,
                outcome_recorded_at=datetime.fromisoformat(decision.outcome_recorded_at)
                if decision.outcome_recorded_at is not None
                else None,
                created_at=datetime.fromisoformat(decision.created_at),
            )
        )
        step.status = "completed"
        step.finished_at = datetime.now(timezone.utc)
        await session.commit()
    return step.step_id


def _extract_user_pinned_research(
    *,
    plan_tree: object,
    researched_competitors: list[str],
) -> list[dict[str, object]]:
    """Phase β: project user-injected research tasks that are still pending.

    Returns a list of {competitor_id, title, focus_dimensions} dicts the
    supervisor prompt builder turns into a "user pinned" hint section. Filters
    out competitors that already appear in `researched_competitors` so we
    don't nag the LLM about work it's already done.
    """
    if not isinstance(plan_tree, dict):
        return []
    tasks_raw = plan_tree.get("tasks")
    if not isinstance(tasks_raw, list):
        return []
    done = set(researched_competitors)
    pinned: list[dict[str, object]] = []
    for task in tasks_raw:
        if not isinstance(task, dict):
            continue
        if task.get("source") != "user":
            continue
        if task.get("priority") != "user_pinned":
            continue
        if task.get("stage") != "research":
            continue
        if task.get("enabled") is False:
            continue
        competitor_id = task.get("competitor_id")
        if not isinstance(competitor_id, str) or not competitor_id.strip():
            continue
        if competitor_id in done:
            continue
        title_raw = task.get("title")
        focus_raw = task.get("focus_dimensions")
        pinned.append(
            {
                "competitor_id": competitor_id,
                "title": title_raw if isinstance(title_raw, str) else "",
                "focus_dimensions": (
                    [f for f in focus_raw if isinstance(f, str)]
                    if isinstance(focus_raw, list)
                    else []
                ),
            }
        )
    return pinned


def _has_pending_plan_discovery(
    *,
    plan_tree: object,
    discovered_competitors: list[str],
    decisions: list[SupervisorDecision],
) -> bool:
    if discovered_competitors:
        return False
    if any(decision.chosen_tool == "DiscoverCompetitors" for decision in decisions):
        return False

    tasks_raw: object
    if isinstance(plan_tree, dict):
        tasks_raw = plan_tree.get("tasks")
    else:
        tasks_raw = getattr(plan_tree, "tasks", None)
    if not isinstance(tasks_raw, list):
        return False

    for task in tasks_raw:
        if isinstance(task, dict):
            stage = task.get("stage")
            enabled = task.get("enabled", True)
        else:
            stage = getattr(task, "stage", None)
            enabled = getattr(task, "enabled", True)
        if stage == "discover" and enabled is not False:
            return True
    return False


async def _load_pending_follow_ups(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
) -> list[dict[str, object]]:
    """Phase 4: read FollowUpEntry rows with consumed_at=None from Run.follow_ups.

    Returns plain dicts (not Pydantic models) — the supervisor prompt builder
    only needs `id` / `text` / `applies_to_stage`, and round-tripping through
    FollowUpEntry would discard fields the FE may add later.
    """
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None or run.follow_ups is None:
            return []
        pending: list[dict[str, object]] = []
        for entry in run.follow_ups:
            if not isinstance(entry, dict):
                continue
            if entry.get("consumed_at") is None:
                pending.append(entry)
    return pending


async def _mark_follow_ups_consumed(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    follow_up_ids: list[str],
    iteration: int,
) -> None:
    """Phase 4: stamp `consumed_at` + `consumed_in_iteration` on the listed IDs.

    Reads + writes inside one session so we don't race with a concurrent
    POST /follow-up. Unknown IDs are silently skipped (defensive — the
    endpoint never reuses fu_ ids so this should never happen).
    """
    if not follow_up_ids:
        return
    target_ids = set(follow_up_ids)
    consumed_at = _now_iso()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None or run.follow_ups is None:
            return
        updated: list[dict[str, object]] = []
        changed = False
        for entry in run.follow_ups:
            if not isinstance(entry, dict):
                updated.append(entry)
                continue
            entry_id = entry.get("id")
            if (
                isinstance(entry_id, str)
                and entry_id in target_ids
                and entry.get("consumed_at") is None
            ):
                new_entry = {
                    **entry,
                    "consumed_at": consumed_at,
                    "consumed_in_iteration": iteration,
                }
                updated.append(new_entry)
                changed = True
            else:
                updated.append(entry)
        if changed:
            run.follow_ups = updated
            await session.commit()


def _map_next_action(chosen_tool: str) -> Literal["discovery", "researcher", "analyst", "writer", "finalize"]:
    if chosen_tool == "DiscoverCompetitors":
        return "discovery"
    if chosen_tool in {"ConductResearch", "ConductResearchBatch"}:
        return "researcher"
    if chosen_tool == "Analyze":
        return "analyst"
    if chosen_tool == "Write":
        return "writer"
    return "finalize"


_CHOSEN_TOOL_TO_PLAN_STAGE: dict[str, str] = {
    "DiscoverCompetitors": "discover",
    "ConductResearch": "research",
    "ConductResearchBatch": "research",
    "Analyze": "analyze",
    "Write": "write",
}


def _match_plan_task_ids(
    *,
    plan_tree: object,
    decision: SupervisorDecision,
) -> list[str]:
    """Best-effort map supervisor decision → plan_task IDs for the live plan tree.

    Returns empty list when:
    - plan_tree is missing / unconfirmed (legacy runs, intake-skip, Finalize),
    - or no task in plan_tree matches the chosen tool + competitor(s).
    The FE uses this list to flip task tiles to "running"; a miss is harmless.
    """
    if not isinstance(plan_tree, dict):
        return []
    tasks_raw = plan_tree.get("tasks")
    if not isinstance(tasks_raw, list):
        return []
    target_stage = _CHOSEN_TOOL_TO_PLAN_STAGE.get(decision.chosen_tool)
    if target_stage is None:
        return []

    target_competitor_ids: set[str] = set()
    if decision.chosen_tool == "ConductResearch":
        competitor_id_raw = decision.tool_args.get("competitor_id")
        if isinstance(competitor_id_raw, str) and competitor_id_raw:
            target_competitor_ids.add(competitor_id_raw)
    elif decision.chosen_tool == "ConductResearchBatch":
        topics_raw = decision.tool_args.get("topics")
        if isinstance(topics_raw, list):
            for topic in topics_raw:
                if isinstance(topic, dict):
                    competitor_id_raw = topic.get("competitor_id")
                    if isinstance(competitor_id_raw, str) and competitor_id_raw:
                        target_competitor_ids.add(competitor_id_raw)

    matched: list[str] = []
    for task in tasks_raw:
        if not isinstance(task, dict):
            continue
        if task.get("stage") != target_stage:
            continue
        if target_stage == "research":
            task_competitor = task.get("competitor_id")
            if not isinstance(task_competitor, str) or task_competitor not in target_competitor_ids:
                continue
        task_id_raw = task.get("task_id")
        if isinstance(task_id_raw, str) and task_id_raw:
            matched.append(task_id_raw)
    return matched


def _has_enabled_write_task(plan_tree: object) -> bool:
    tasks_raw: object
    if isinstance(plan_tree, dict):
        tasks_raw = plan_tree.get("tasks")
    else:
        tasks_raw = getattr(plan_tree, "tasks", None)
    if not isinstance(tasks_raw, list):
        return False
    for task in tasks_raw:
        if isinstance(task, dict):
            stage = task.get("stage")
            enabled = task.get("enabled", True)
        else:
            stage = getattr(task, "stage", None)
            enabled = getattr(task, "enabled", True)
        if stage == "write" and enabled is not False:
            return True
    return False


def _build_landscape_batch_topics(
    *,
    competitors: list[str],
    researched_competitors: list[str],
    plan_tree: object,
    fallback_dimensions: list[str],
    profile: TierProfile,
    user_query: str,
) -> list[ConductResearch]:
    researched_set = set(researched_competitors)
    pending_competitors = [
        competitor
        for competitor in competitors
        if competitor not in researched_set
    ][: profile.max_competitors]
    if not pending_competitors:
        return []
    task_focus_by_competitor: dict[str, list[str]] = {}
    task_topic_by_competitor: dict[str, str] = {}
    tasks_raw = _get_object_field(plan_tree, "tasks")
    if isinstance(tasks_raw, list):
        for task in tasks_raw:
            if _get_object_field(task, "stage") != "research":
                continue
            if _get_object_field(task, "enabled") is False:
                continue
            competitor_id = _clean_optional_string(_get_object_field(task, "competitor_id"))
            if competitor_id is None or competitor_id not in pending_competitors:
                continue
            focus_dimensions_raw = _get_object_field(task, "focus_dimensions")
            if not isinstance(focus_dimensions_raw, list):
                continue
            normalized = normalize_dimensions(
                [
                    item
                    for item in focus_dimensions_raw
                    if isinstance(item, str)
                ],
                allow_empty=True,
            )
            if not normalized:
                continue
            title = _clean_optional_string(_get_object_field(task, "title"))
            description = _clean_optional_string(_get_object_field(task, "description"))
            topic_text = description or title
            if topic_text is not None:
                task_topic_by_competitor[competitor_id] = topic_text
            # Keep planner-provided per-competitor focus dimensions intact for
            # landscape runs, while preserving the schema trio when present.
            cap = profile.max_dimensions
            if all(dimension in normalized for dimension in COMPARISON_SCHEMA_BASE_DIMENSIONS):
                cap = max(cap, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
            task_focus_by_competitor[competitor_id] = normalized[:cap]
    source_context_by_competitor: dict[str, str] = {}
    role_by_competitor: dict[str, str | None] = {}
    competitor_sources_raw = _get_object_field(plan_tree, "competitor_sources")
    if isinstance(competitor_sources_raw, dict):
        for competitor_id in pending_competitors:
            payload = competitor_sources_raw.get(competitor_id)
            if not isinstance(payload, dict):
                continue
            role_raw = _clean_optional_string(payload.get("candidate_role"))
            role_by_competitor[competitor_id] = role_raw
            relevance_reason = _clean_optional_string(payload.get("relevance_reason"))
            if relevance_reason is None:
                continue
            source_context_by_competitor[competitor_id] = " ".join(relevance_reason.split())[:240]
    deepdive_cap = max(0, min(len(pending_competitors), profile.landscape_core_deepdive_n))
    landscape_core_competitors = [
        competitor
        for competitor in pending_competitors
        if role_by_competitor.get(competitor) in _LANDSCAPE_CORE_ROLES
    ][:deepdive_cap]
    if not landscape_core_competitors and deepdive_cap > 0:
        fallback_non_upstream = [
            competitor
            for competitor in pending_competitors
            if role_by_competitor.get(competitor) != "upstream_supplier"
        ]
        landscape_core_competitors = fallback_non_upstream[:deepdive_cap]
    landscape_core_set = set(landscape_core_competitors)
    topics: list[ConductResearch] = []
    for competitor_id in pending_competitors:
        competitor_focus = task_focus_by_competitor.get(competitor_id) or list(fallback_dimensions)
        competitor_focus = normalize_dimensions(
            [item for item in competitor_focus if isinstance(item, str)],
            allow_empty=True,
        )
        if competitor_id in landscape_core_set:
            competitor_focus = ensure_comparison_schema_dimensions(
                competitor_focus,
                analysis_archetype="landscape",
                force_schema_dimensions=True,
            )
            extras = [
                dimension
                for dimension in competitor_focus
                if dimension not in COMPARISON_SCHEMA_BASE_DIMENSIONS
            ]
            competitor_focus = [*COMPARISON_SCHEMA_BASE_DIMENSIONS, *extras]
            cap = max(profile.max_dimensions, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
        elif all(
            dimension in competitor_focus for dimension in COMPARISON_SCHEMA_BASE_DIMENSIONS
        ):
            cap = max(profile.max_dimensions, len(COMPARISON_SCHEMA_BASE_DIMENSIONS))
        else:
            cap = profile.max_dimensions
        competitor_focus = competitor_focus[:cap]
        research_topic = task_topic_by_competitor.get(competitor_id) or (
            f"{competitor_id} vs user_query={user_query}"
        )
        context = source_context_by_competitor.get(competitor_id)
        if context and context not in research_topic:
            research_topic = f"{research_topic}; context: {context}"
        topics.append(
            ConductResearch(
                research_topic=research_topic,
                competitor_id=competitor_id,
                focus_dimensions=competitor_focus,
                max_iterations=profile.react_turns,
                search_max_results=profile.search_max_results,
                fallback_to_offline=True,
            )
        )
    return topics


def _coerce_landscape_topic_signature(
    *,
    topic_raw: object,
    profile: TierProfile,
) -> LandscapeTopicSignature | None:
    if not isinstance(topic_raw, dict):
        return None
    competitor_id = _clean_optional_string(topic_raw.get("competitor_id"))
    if competitor_id is None:
        return None
    focus_raw = topic_raw.get("focus_dimensions")
    focus_dimensions = (
        normalize_dimensions(
            [item for item in focus_raw if isinstance(item, str)],
            allow_empty=True,
        )
        if isinstance(focus_raw, list)
        else []
    )
    max_iterations_raw = topic_raw.get("max_iterations")
    max_iterations = (
        min(max_iterations_raw, profile.react_turns)
        if isinstance(max_iterations_raw, int) and max_iterations_raw > 0
        else profile.react_turns
    )
    search_max_results_raw = topic_raw.get("search_max_results")
    search_max_results = (
        min(search_max_results_raw, profile.search_max_results)
        if isinstance(search_max_results_raw, int) and search_max_results_raw > 0
        else profile.search_max_results
    )
    fallback_to_offline_raw = topic_raw.get("fallback_to_offline")
    fallback_to_offline = (
        bool(fallback_to_offline_raw)
        if fallback_to_offline_raw is not None
        else True
    )
    return (
        competitor_id,
        tuple(focus_dimensions),
        max_iterations,
        search_max_results,
        fallback_to_offline,
    )


def _landscape_topic_signatures_for_decision(
    *,
    decision: SupervisorDecision,
    profile: TierProfile,
) -> list[LandscapeTopicSignature]:
    if decision.chosen_tool == "ConductResearch":
        signature = _coerce_landscape_topic_signature(
            topic_raw=decision.tool_args,
            profile=profile,
        )
        return [signature] if signature is not None else []
    topics_raw = decision.tool_args.get("topics")
    if not isinstance(topics_raw, list):
        return []
    signatures: list[LandscapeTopicSignature] = []
    for topic_raw in topics_raw:
        signature = _coerce_landscape_topic_signature(
            topic_raw=topic_raw,
            profile=profile,
        )
        if signature is None:
            continue
        signatures.append(signature)
    return signatures


def _enforce_landscape_batch_research(
    *,
    decision: SupervisorDecision,
    run_id: str,
    iteration: int,
    triggered_by: TriggerSource,
    intake_draft: object,
    competitors: list[str],
    researched_competitors: list[str],
    plan_tree: object,
    fallback_dimensions: list[str],
    profile: TierProfile,
    user_query: str,
) -> SupervisorDecision:
    if _analysis_archetype_from_intake(intake_draft) != "landscape":
        return decision
    if decision.chosen_tool not in {"ConductResearch", "ConductResearchBatch"}:
        return decision
    topics = _build_landscape_batch_topics(
        competitors=competitors,
        researched_competitors=researched_competitors,
        plan_tree=plan_tree,
        fallback_dimensions=fallback_dimensions,
        profile=profile,
        user_query=user_query,
    )
    if len(topics) <= 1:
        return decision
    selected_topic_signatures: list[LandscapeTopicSignature] = [
        (
            topic.competitor_id,
            tuple(normalize_dimensions(list(topic.focus_dimensions), allow_empty=True)),
            topic.max_iterations,
            topic.search_max_results,
            bool(topic.fallback_to_offline),
        )
        for topic in topics
    ]
    current_topic_signatures = _landscape_topic_signatures_for_decision(
        decision=decision,
        profile=profile,
    )
    if current_topic_signatures == selected_topic_signatures:
        return decision

    now = _now_iso()
    return SupervisorDecision(
        id=make_id("decision_"),
        run_id=run_id,
        iteration=iteration,
        chosen_tool="ConductResearchBatch",
        tool_args=ConductResearchBatch(
            topics=topics,
            parallelism_rationale=(
                "Landscape guardrail enforces batch research for all pending competitors in this iteration."
            ),
        ).model_dump(),
        reasoning_summary=(
            "Landscape guardrail rewrote research dispatch to batch mode to keep representative "
            "competitor deep-dive within the iteration budget."
        ),
        triggered_by=triggered_by,
        outcome="dispatched",
        outcome_recorded_at=now,
        created_at=now,
    )


def _enforce_deliverable_before_finalize(
    *,
    decision: SupervisorDecision,
    plan_tree: object,
    report_draft_done: bool,
    prior_decisions: list[SupervisorDecision],
    fallback_sections: list[str],
) -> SupervisorDecision:
    if decision.chosen_tool != "Finalize":
        return decision
    if report_draft_done:
        return decision

    has_enabled_write_task = _has_enabled_write_task(plan_tree)
    has_historical_write = any(item.chosen_tool == "Write" for item in prior_decisions)
    if has_enabled_write_task and not has_historical_write:
        now = _now_iso()
        return SupervisorDecision(
            id=make_id("decision_"),
            run_id=decision.run_id,
            iteration=decision.iteration,
            chosen_tool="Write",
            tool_args=Write(
                template_id=None,
                sections=fallback_sections,
            ).model_dump(),
            reasoning_summary=(
                "Finalize blocked: no report draft yet; route one writer pass before finalizing."
            ),
            triggered_by=decision.triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )

    # No report exists and we cannot/should not reroute writer again.
    # Force degraded-finalize semantics to keep run status truthful.
    return decision.model_copy(
        update={
            "tool_args": Finalize(
                completion_reason="fallback_path",
                notes=(
                    "Finalize without report draft; writer already attempted or no enabled "
                    "write task exists."
                ),
            ).model_dump(),
            "reasoning_summary": (
                "Forced degraded finalize: no report draft and no further writer reroute available."
            ),
            "outcome": "succeeded",
        }
    )


@log_node("supervisor")
async def supervisor_node(state: AgentState) -> AgentState:
    session_factory = _resolve_session_factory(state)

    run_id = state.get("run_id", make_id("run_"))
    decisions = list(state.get("decisions", []))
    user_query_raw = state.get("user_query", "")
    user_query = user_query_raw if isinstance(user_query_raw, str) else ""
    market_scope = _state_or_intake_string(state, "market_scope")
    domain_context = (
        _state_or_intake_string(state, "domain_hint")
        or _state_or_intake_string(state, "analysis_intent")
    )
    response_language = _state_response_language(state, user_query=user_query)
    competitors_raw = list(state.get("competitors", []))
    discovered_competitors = list(state.get("discovered_competitors", []))
    researched_competitors = list(state.get("researched_competitors", []))
    analysis_done = bool(state.get("analysis_done", False))
    report_draft_done = bool(state.get("report_draft_done", False))
    qa_outcome = state.get("qa_outcome")
    qa_reject_to = state.get("qa_reject_to")
    pending_review_target_step_id = state.get("pending_review_target_step_id")
    qa_reasons = list(state.get("qa_reasons", []))
    qa_degrade_reason_raw = state.get("qa_degrade_reason")
    qa_degrade_reason = (
        qa_degrade_reason_raw
        if isinstance(qa_degrade_reason_raw, str) and qa_degrade_reason_raw.strip()
        else None
    )
    qa_degraded_required_sections = [
        item
        for item in state.get("qa_degraded_required_sections", [])
        if isinstance(item, str)
    ]
    qa_unsupported_numeric_claims_current = [
        item
        for item in state.get("qa_unsupported_numeric_claims", [])
        if isinstance(item, dict)
    ]
    qa_numeric_claim_blocklist = [
        item
        for item in state.get("qa_numeric_claim_blocklist", [])
        if isinstance(item, dict)
    ]
    qa_unsupported_numeric_claims = _stable_numeric_claims(
        [*qa_numeric_claim_blocklist, *qa_unsupported_numeric_claims_current]
    )
    report_depth = _state_or_intake_string(state, "report_depth")
    tier_profile = resolve_tier_profile(report_depth)
    competitors = competitors_raw[:tier_profile.max_competitors]
    iteration = int(state.get("current_iteration", 0)) + 1
    last_completed_node = state.get("last_completed_node")
    triggered_by = _resolve_triggered_by(
        iteration=iteration,
        last_completed_node=last_completed_node,
        qa_outcome=qa_outcome,
    )
    fallback_dimensions, dimension_source = _resolve_fallback_dimensions(
        plan_tree=state.get("plan_tree"),
        intake_draft=state.get("intake_draft"),
        user_query=user_query,
        competitors=competitors,
        researched_competitors=researched_competitors,
        analysis_done=analysis_done,
        report_draft_done=report_draft_done,
        qa_reject_to=qa_reject_to if qa_outcome in {"rejected", "force_degraded"} else None,
        max_dimensions=tier_profile.max_dimensions,
    )
    fallback_sections = _derive_write_sections(
        focus_dimensions=fallback_dimensions,
    )
    decision_dimension_source: FocusDimensionSource | None = None

    # Pre-declared so the mark-consumed call after persist is unconditional;
    # only the LLM branch overwrites it (qa-driven + max-iter branches don't
    # actually present the follow-ups to any LLM, so we leave them pending).
    pending_follow_ups: list[dict[str, object]] = []

    forced_degraded_by_qa = False
    qa_driven_decision = await _decision_from_qa_feedback(
        session_factory=session_factory,
        run_id=run_id,
        iteration=iteration,
        triggered_by=triggered_by,
        qa_outcome=qa_outcome,
        qa_reject_to=qa_reject_to,
        qa_reasons=qa_reasons,
        qa_degrade_reason=qa_degrade_reason,
        qa_degraded_required_sections=qa_degraded_required_sections,
        qa_unsupported_numeric_claims=qa_unsupported_numeric_claims,
        user_query=user_query,
        competitors=competitors,
        fallback_dimensions=fallback_dimensions,
        fallback_sections=fallback_sections,
        pending_review_target_step_id=(
            pending_review_target_step_id
            if isinstance(pending_review_target_step_id, str)
            else None
        ),
        profile=tier_profile,
    )
    if qa_driven_decision is not None:
        decision, llm_response, forced_degraded_by_qa = qa_driven_decision
        if decision.chosen_tool in _DIMENSIONAL_SUPERVISOR_TOOLS:
            decision_dimension_source = dimension_source
    elif iteration > tier_profile.supervisor_max_iterations:
        forced_now = _now_iso()
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="Finalize",
            tool_args=Finalize(
                completion_reason="max_iterations_hit",
                notes=(
                    "Supervisor reached max iterations and forced finalize "
                    f"(limit={tier_profile.supervisor_max_iterations})."
                ),
            ).model_dump(),
            reasoning_summary="Forced finalize due to supervisor max iteration guardrail.",
            triggered_by="iteration_advance",
            outcome="succeeded",
            outcome_recorded_at=forced_now,
            created_at=forced_now,
        )
        llm_response = _pseudo_llm_response(
            provider="guardrail",
            model_name="guardrail",
            prompt_preview="max_iterations_hit",
            error="max_iterations_hit",
        )
    elif _has_pending_plan_discovery(
        plan_tree=state.get("plan_tree"),
        discovered_competitors=discovered_competitors,
        decisions=decisions,
    ):
        now = _now_iso()
        domain_for_discovery = domain_context or user_query
        decision = SupervisorDecision(
            id=make_id("decision_"),
            run_id=run_id,
            iteration=iteration,
            chosen_tool="DiscoverCompetitors",
            tool_args=DiscoverCompetitors(
                search_queries=_discovery_search_queries(
                    user_query=user_query,
                    domain_context=domain_context,
                    market_scope=market_scope,
                    response_language=response_language,
                ),
                domain_context=domain_for_discovery,
                max_results=DEFAULT_DISCOVER_MAX_RESULTS,
            ).model_dump(),
            reasoning_summary="Plan includes a pending discovery task; discover additional competitors before research.",
            triggered_by=triggered_by,
            outcome="dispatched",
            outcome_recorded_at=now,
            created_at=now,
        )
        llm_response = _pseudo_llm_response(
            provider="guardrail",
            model_name="guardrail",
            prompt_preview="plan_pending_discovery",
            error=None,
        )
    elif _should_route_write_after_analysis(
        competitors=competitors,
        researched_competitors=researched_competitors,
        prior_decisions=decisions,
        analysis_done=analysis_done,
        report_draft_done=report_draft_done,
        qa_outcome=qa_outcome,
        qa_reject_to=qa_reject_to,
    ):
        decision = _write_after_analysis_decision(
            run_id=run_id,
            iteration=iteration,
            triggered_by=triggered_by,
            fallback_sections=fallback_sections,
            reason=(
                "Analysis already completed for current scope; deterministic gate routes "
                "to writer instead of asking supervisor LLM for another analysis pass."
            ),
        )
        decision_dimension_source = dimension_source
        llm_response = _pseudo_llm_response(
            provider="supervisor_state_gate",
            model_name="supervisor_state_gate",
            prompt_preview="analysis_done_route_write",
            error=None,
        )
    else:
        pending_follow_ups = await _load_pending_follow_ups(
            session_factory=session_factory,
            run_id=run_id,
        )
        plan_tree_raw = state.get("plan_tree")
        if isinstance(plan_tree_raw, dict):
            plan_tree_for_prompt: dict[str, object] | None = plan_tree_raw
        elif hasattr(plan_tree_raw, "model_dump"):
            plan_tree_for_prompt = plan_tree_raw.model_dump()
        else:
            plan_tree_for_prompt = None
        user_pinned_research = _extract_user_pinned_research(
            plan_tree=state.get("plan_tree"),
            researched_competitors=researched_competitors,
        )
        discovery_completed = bool(discovered_competitors) or any(
            prior.chosen_tool == "DiscoverCompetitors" for prior in decisions
        )
        user_prompt = build_supervisor_user_prompt(
            user_query=user_query,
            iteration=iteration,
            competitors=competitors,
            researched_competitors=researched_competitors,
            analysis_done=analysis_done,
            report_draft_done=report_draft_done,
            qa_outcome=qa_outcome,
            qa_reject_to=qa_reject_to,
            qa_reasons=qa_reasons,
            market_scope=market_scope,
            domain_context=domain_context,
            pending_follow_ups=pending_follow_ups,
            user_pinned_research=user_pinned_research,
            plan_tree=plan_tree_for_prompt,
            discovery_completed=discovery_completed,
        )
        fallback_user_prompt = build_supervisor_fallback_user_prompt(
            user_query=user_query,
            competitors=competitors,
            researched_competitors=researched_competitors,
            analysis_done=analysis_done,
            report_draft_done=report_draft_done,
            market_scope=market_scope,
            domain_context=domain_context,
            pending_follow_ups=pending_follow_ups,
            user_pinned_research=user_pinned_research,
            plan_tree=plan_tree_for_prompt,
            discovery_completed=discovery_completed,
        )
        harness_result = await complete_structured(
            model_slot="research",
            system_prompt=SUPERVISOR_SYSTEM_PROMPT,
            user_prompt=user_prompt,
            output_model=SupervisorToolCallOutput,
            parser=SupervisorToolCallOutput.parse_llm_content,
            fallback_system_prompt=SUPERVISOR_SYSTEM_PROMPT,
            fallback_user_prompt=fallback_user_prompt,
            repair_user_prompt_builder=lambda errors: build_supervisor_repair_user_prompt(
                validation_errors=errors,
                user_query=user_query,
                iteration=iteration,
                competitors=competitors,
                plan_tree=plan_tree_for_prompt,
            ),
            log_event="supervisor.harness.finish",
        )
        llm_response = harness_result.llm_response
        if harness_result.value is not None:
            decision = _decision_from_tool_output(
                run_id=run_id,
                iteration=iteration,
                output=harness_result.value,
                triggered_by=triggered_by,
                fallback_dimensions=fallback_dimensions,
                fallback_sections=fallback_sections,
                profile=tier_profile,
            )
            if (
                decision.chosen_tool == "DiscoverCompetitors"
                and discovery_completed
                and competitors
            ):
                # Hard stop on discovery loops: the LLM occasionally re-picks
                # discovery from a stale plan task and burns the whole
                # iteration budget without ever reaching analyze/write.
                log.warning(
                    "supervisor.guardrail.discovery_repeat_blocked",
                    iteration=iteration,
                    blocked_reasoning=decision.reasoning_summary[:120],
                )
                decision = _fallback_decision(
                    run_id=run_id,
                    iteration=iteration,
                    competitors=competitors,
                    researched_competitors=researched_competitors,
                    analysis_done=analysis_done,
                    report_draft_done=report_draft_done,
                    triggered_by=triggered_by,
                    user_query=user_query,
                    fallback_dimensions=fallback_dimensions,
                    fallback_sections=fallback_sections,
                    profile=tier_profile,
                    market_scope=market_scope,
                    domain_context=domain_context,
                    response_language=response_language,
                )
            if (
                decision.chosen_tool == "Analyze"
                and _should_route_write_after_analysis(
                    competitors=competitors,
                    researched_competitors=researched_competitors,
                    prior_decisions=decisions,
                    analysis_done=analysis_done,
                    report_draft_done=report_draft_done,
                    qa_outcome=qa_outcome,
                    qa_reject_to=qa_reject_to,
                )
            ):
                log.warning(
                    "supervisor.guardrail.analyze_after_analysis_blocked",
                    iteration=iteration,
                    blocked_reasoning=decision.reasoning_summary[:120],
                )
                decision = _write_after_analysis_decision(
                    run_id=run_id,
                    iteration=iteration,
                    triggered_by=triggered_by,
                    fallback_sections=fallback_sections,
                    reason=(
                        "Analyze blocked: current analysis scope is already complete; "
                        "route to writer to produce the deliverable."
                    ),
                )
            if decision.chosen_tool in _DIMENSIONAL_SUPERVISOR_TOOLS:
                decision_dimension_source = dimension_source
        else:
            decision = _fallback_decision(
                run_id=run_id,
                iteration=iteration,
                competitors=competitors,
                researched_competitors=researched_competitors,
                analysis_done=analysis_done,
                report_draft_done=report_draft_done,
                triggered_by=triggered_by,
                user_query=user_query,
                fallback_dimensions=fallback_dimensions,
                fallback_sections=fallback_sections,
                profile=tier_profile,
                market_scope=market_scope,
                domain_context=domain_context,
                response_language=response_language,
            )
            decision_dimension_source = dimension_source

    discovery_attempted = bool(discovered_competitors) or any(
        prior.chosen_tool == "DiscoverCompetitors" for prior in decisions
    )
    if (
        not competitors
        and not discovery_attempted
        and decision.chosen_tool
        not in {"DiscoverCompetitors", "ConductResearch", "ConductResearchBatch"}
    ):
        log.warning(
            "supervisor.guardrail.empty_competitors_blocked",
            iteration=iteration,
            blocked_tool=decision.chosen_tool,
        )
        decision = _fallback_decision(
            run_id=run_id,
            iteration=iteration,
            competitors=competitors,
            researched_competitors=researched_competitors,
            analysis_done=analysis_done,
            report_draft_done=report_draft_done,
            triggered_by=triggered_by,
            user_query=user_query,
            fallback_dimensions=fallback_dimensions,
            fallback_sections=fallback_sections,
            profile=tier_profile,
            market_scope=market_scope,
            domain_context=domain_context,
            response_language=response_language,
        )
        decision_dimension_source = dimension_source

    decision = _enforce_landscape_batch_research(
        decision=decision,
        run_id=run_id,
        iteration=iteration,
        triggered_by=triggered_by,
        intake_draft=state.get("intake_draft"),
        competitors=competitors,
        researched_competitors=researched_competitors,
        plan_tree=state.get("plan_tree"),
        fallback_dimensions=fallback_dimensions,
        profile=tier_profile,
        user_query=user_query,
    )
    decision = _enforce_deliverable_before_finalize(
        decision=decision,
        plan_tree=state.get("plan_tree"),
        report_draft_done=report_draft_done,
        prior_decisions=decisions,
        fallback_sections=fallback_sections,
    )
    if decision.chosen_tool in _DIMENSIONAL_SUPERVISOR_TOOLS and decision_dimension_source is None:
        decision_dimension_source = dimension_source

    persisted_step_id = await _persist_iteration(
        session_factory=session_factory,
        run_id=run_id,
        iteration=iteration,
        decision=decision,
        llm_response=llm_response,
    )
    consumed_follow_up_ids: list[str] = []
    for entry in pending_follow_ups:
        entry_id = entry.get("id")
        if isinstance(entry_id, str) and entry_id:
            consumed_follow_up_ids.append(entry_id)
    if consumed_follow_up_ids:
        await _mark_follow_ups_consumed(
            session_factory=session_factory,
            run_id=run_id,
            follow_up_ids=consumed_follow_up_ids,
            iteration=iteration,
        )
    with bind_step(persisted_step_id):
        log.info(
            "supervisor.decision",
            iteration=iteration,
            chosen_tool=decision.chosen_tool,
            triggered_by=decision.triggered_by,
            outcome=decision.outcome,
            reasoning_summary_len=len(decision.reasoning_summary),
            tool_arg_keys=sorted(decision.tool_args.keys()),
            dimension_source=decision_dimension_source,
        )
    plan_task_ids = _match_plan_task_ids(
        plan_tree=state.get("plan_tree"),
        decision=decision,
    )
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.SUPERVISOR_DECISION,
        step_id=persisted_step_id,
        payload={
            "iteration": iteration,
            "chosen_tool": decision.chosen_tool,
            "triggered_by": decision.triggered_by or "unknown",
            "outcome": decision.outcome or "unknown",
            "plan_task_ids": plan_task_ids,
            "consumed_follow_up_ids": consumed_follow_up_ids,
            "dimension_source": decision_dimension_source,
        },
    )
    decisions.append(decision)

    next_action = _map_next_action(decision.chosen_tool)
    completion_reason = str(decision.tool_args.get("completion_reason", ""))
    next_analysis_done = analysis_done
    next_report_draft_done = report_draft_done
    if decision.chosen_tool in {"ConductResearch", "ConductResearchBatch"}:
        # Fresh research invalidates prior downstream artifacts; force analysis+write rerun.
        next_analysis_done = False
        next_report_draft_done = False
    elif decision.chosen_tool == "Analyze":
        # Re-analysis requires a fresh writer pass before finalize.
        next_report_draft_done = False

    if decision.chosen_tool == "Finalize":
        writer_fallback = bool(state.get("writer_report_fallback_mode"))
        researcher_degraded_competitors = [
            item
            for item in state.get("researcher_degraded_competitors", [])
            if isinstance(item, str) and item
        ]
        degraded_required_sections = qa_degraded_required_sections or [
            item
            for item in state.get("report_degraded_required_sections", [])
            if isinstance(item, str) and item
        ]
        if (
            not report_draft_done
            or completion_reason in {"max_iterations_hit", "fallback_path"}
            or forced_degraded_by_qa
            or writer_fallback
            or researcher_degraded_competitors
        ):
            status = "degraded"
            status_reason = build_degraded_reason(
                forced_degraded_by_qa=forced_degraded_by_qa,
                qa_degrade_reason=qa_degrade_reason,
                degraded_required_sections=degraded_required_sections,
                writer_fallback=writer_fallback,
                completion_reason=completion_reason,
                report_draft_done=report_draft_done,
                researcher_degraded_competitors=researcher_degraded_competitors,
                competitor_count=len(competitors),
            )
        else:
            status = "completed"
            status_reason = None
    else:
        status = "running"
        status_reason = None

    return {
        **spread_without_accumulators(state),
        "run_id": run_id,
        "decisions": decisions,
        "current_iteration": iteration,
        "pending_tool_args": decision.tool_args,
        "next_action": next_action,
        "last_completed_node": None,
        "analysis_done": next_analysis_done,
        "report_draft_done": next_report_draft_done,
        "qa_outcome": None,
        "qa_reject_to": None,
        "qa_reasons": [],
        "qa_degrade_reason": None,
        "qa_degraded_required_sections": [],
        "qa_unsupported_numeric_claims": [],
        "status": status,
        "status_reason": status_reason,
    }
