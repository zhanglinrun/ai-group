from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re
from typing import Any, Awaitable, Literal
from uuid import uuid4

from fastapi import APIRouter, Depends, Header, Query, Request
from fastapi.responses import StreamingResponse
from langgraph.types import Command
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import case, delete, func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
import yaml

from core.defaults import DEFAULT_FOCUS_DIMENSIONS
from core.tiers import resolve_tier_profile
from db.engine import get_session_factory
from core.config import settings
from exceptions.base import APIException
from models.competitor_diff import CompetitorDiff
from models.conclusion import ConclusionRecord
from models.evidence import EvidenceRecord
from models.llm_call import LLMCall
from models.report import Report
from models.run import Run
from models.run_create_request import RunCreateRequestRecord
from models.skill_candidate import SkillCandidateRecord
from models.step import Step
from models.supervisor_decision import SupervisorDecisionRecord
from models.watchlist import WatchlistItem
from schemas.ids import make_id
from schemas.intake import (
    IntakeClarifyRequest,
    IntakeExchange,
    IntakeUserReply,
    RunIntakeDraft,
    UserRole,
    normalize_optional_text,
    stable_unique_text,
)
from schemas.plan import FollowUpEntry, FollowUpRequest, PlanConfirmRequest
from service.comparison import load_comparisons_for_run
from service.conclusion import load_conclusions_for_run
from service.diff.comparator import compute_diff
from service.event_bus import EventBus, RunEventType, emit_run_event
from service.billing import Reservation, default_reservation_amount, quota_client
from security.identity import get_identity, require_identity
from service.knowledge import load_knowledge_for_run
from service.locale import resolve_report_language
from service.metrics import RunMetricsSnapshot, build_run_metrics_snapshot, load_run_metrics_snapshot
from service.skill_curator.tasks import run_skill_curator_for_run
from utils.logger import bind_run, format_exception_for_log, get_logger

router = APIRouter(dependencies=[Depends(require_identity)])
log = get_logger("router.run_rt")

_RUN_PROGRESS_INTERVAL_SECONDS = 180


def _resolve_run_report_depth(run: Run | None) -> str | None:
    if run is None or not isinstance(run.intake_draft, dict):
        return None
    report_depth_raw = run.intake_draft.get("report_depth")
    if isinstance(report_depth_raw, str):
        return report_depth_raw
    return None


def _graph_invoke_config(*, run_id: str, report_depth: str | None) -> dict[str, object]:
    profile = resolve_tier_profile(report_depth)
    return {
        "configurable": {"thread_id": run_id},
        "recursion_limit": profile.recursion_limit,
    }


async def _run_graph_with_progress_heartbeat(
    *,
    run_id: str,
    phase: str,
    graph: Any,
    config: dict[str, object],
    invoke_coro: Awaitable[Any],
) -> Any:
    """Emit structlog heartbeats while a long graph.ainvoke is in flight."""
    started_at = datetime.now(timezone.utc)
    stop_event = asyncio.Event()

    async def _heartbeat_loop() -> None:
        while not stop_event.is_set():
            try:
                await asyncio.wait_for(stop_event.wait(), timeout=_RUN_PROGRESS_INTERVAL_SECONDS)
            except asyncio.TimeoutError:
                checkpoint_next: str | None = None
                try:
                    snapshot = await graph.aget_state(config)
                    if snapshot.next:
                        checkpoint_next = str(snapshot.next[0])
                except (AttributeError, RuntimeError, TypeError, ValueError):
                    checkpoint_next = None
                log.debug(
                    "run.progress",
                    run_id=run_id,
                    phase=phase,
                    elapsed_ms=int(
                        (datetime.now(timezone.utc) - started_at).total_seconds() * 1000
                    ),
                    checkpoint_next=checkpoint_next,
                )

    heartbeat_task = asyncio.create_task(_heartbeat_loop(), name=f"run_progress_{run_id}")
    try:
        return await invoke_coro
    finally:
        stop_event.set()
        heartbeat_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await heartbeat_task

ResetToStage = Literal["analyst", "writer"]
RESETTABLE_RUN_STATUS = {"completed", "degraded"}
RESET_STAGE_AGENT_NAMES: dict[ResetToStage, tuple[str, ...]] = {
    "writer": ("writer", "qa", "skill_curator"),
    "analyst": ("analyst", "writer", "qa", "skill_curator"),
}
RESET_STAGE_DECISION_TOOLS: dict[ResetToStage, tuple[str, ...]] = {
    "writer": ("Write", "Finalize"),
    "analyst": ("Analyze", "Write", "Finalize"),
}


class RunCreateRequest(BaseModel):
    user_query: str = Field(min_length=1)
    competitors: list[str] = Field(default_factory=list)
    domain_hint: str | None = None
    reference_urls: list[str] | None = None
    target_roles: list[str] = Field(default_factory=list)
    report_depth: Literal["debug", "quick", "deep"] = "quick"
    response_language: Literal["zh", "en"] | None = None
    self_product: str | None = None
    market_scope: str | None = None
    time_context: str | None = None
    target_category: str | None = None
    category_aliases: list[str] = Field(default_factory=list)
    excluded_categories: list[str] = Field(default_factory=list)
    market_segments: list[str] = Field(default_factory=list)
    max_micro_points: int | None = Field(default=None, ge=1, le=100_000_000)

    @field_validator("domain_hint", "self_product", "market_scope", "time_context", "target_category")
    @classmethod
    def _normalize_domain_hint(cls, value: str | None) -> str | None:
        return normalize_optional_text(value)

    @field_validator("category_aliases", "excluded_categories", "market_segments", mode="before")
    @classmethod
    def _normalize_category_lists(cls, value: object) -> list[str]:
        if not isinstance(value, list):
            return []
        return stable_unique_text([item for item in value if isinstance(item, str)])

    @field_validator("reference_urls")
    @classmethod
    def _normalize_reference_urls(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        normalized: list[str] = []
        seen: set[str] = set()
        for item in value:
            cleaned = item.strip()
            if not cleaned or cleaned in seen:
                continue
            seen.add(cleaned)
            normalized.append(cleaned)
        return normalized


class RunCreateResponse(BaseModel):
    run_id: str
    status: str
    message: str
    reserved_micro_points: int = 0


class IntakeCreateRequest(BaseModel):
    """Body for POST /api/runs/intake.

    Chat mode (default): only `user_query` (+ optional `user_role`) is expected; the
    Agent clarifies the rest. Expert mode (`?mode=expert`): the caller pre-fills the
    full draft and the Agent skips clarification.
    """

    user_query: str
    user_role: UserRole | None = None
    domain_hint: str | None = None
    reference_urls: list[str] | None = None
    competitors_explicit: list[str] = Field(default_factory=list)
    competitors_discovery_mode: bool = False
    focus_dimensions: list[str] = Field(default_factory=list)
    report_depth: Literal["debug", "quick", "deep"] = "quick"
    response_language: Literal["zh", "en"] | None = None
    from_run_id: str | None = None
    seed_competitor_ids: list[str] = Field(default_factory=list)
    client_request_id: str | None = None
    target_category: str | None = None
    category_aliases: list[str] = Field(default_factory=list)
    excluded_categories: list[str] = Field(default_factory=list)
    market_segments: list[str] = Field(default_factory=list)

    @field_validator("client_request_id", "target_category")
    @classmethod
    def _normalize_client_request_id(cls, value: str | None) -> str | None:
        return normalize_optional_text(value)

    @field_validator("from_run_id")
    @classmethod
    def _normalize_from_run_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None

    @field_validator("seed_competitor_ids")
    @classmethod
    def _normalize_seed_competitor_ids(cls, value: list[str]) -> list[str]:
        normalized: list[str] = []
        seen: set[str] = set()
        for raw in value:
            item = raw.strip()
            if not item or item in seen:
                continue
            seen.add(item)
            normalized.append(item)
        return normalized

    @field_validator("category_aliases", "excluded_categories", "market_segments", mode="before")
    @classmethod
    def _normalize_category_lists(cls, value: object) -> list[str]:
        if not isinstance(value, list):
            return []
        return stable_unique_text([item for item in value if isinstance(item, str)])


class IntakeCreateResponse(BaseModel):
    run_id: str
    status: str
    phase: str
    intake_draft: RunIntakeDraft
    # Async create contract: first clarify arrives via SSE. Keep this optional field
    # for backward-compatibility with older clients that still read it.
    first_clarify_request: IntakeClarifyRequest | None = None


class RunAcceptedResponse(BaseModel):
    """Async-accept envelope for all resume endpoints (Invariant C)."""

    run_id: str
    status: str


class FollowUpAcceptedResponse(BaseModel):
    """Phase 4: POST /follow-up response. `follow_up_id` lets the FE display
    the entry in any "pending instructions" UI before the supervisor consumes it.
    """

    run_id: str
    follow_up_id: str
    received_at: str


class RunResetRequest(BaseModel):
    reset_to: ResetToStage


class RunDetailResponse(BaseModel):
    run_id: str
    owner_user_id: int = 0
    reservation_id: str | None = None
    reserved_micro_points: int = 0
    consumed_micro_points: int = 0
    billing_price_version: str = settings.BILLING_PRICE_VERSION
    billing_input_micro_points_per_token: int = settings.BILLING_INPUT_MICRO_POINTS_PER_TOKEN
    billing_output_micro_points_per_token: int = settings.BILLING_OUTPUT_MICRO_POINTS_PER_TOKEN
    billing_status: str = "NOT_STARTED"
    user_query: str
    # LLM-generated short label populated at intake.complete. Nullable for
    # legacy runs and brief intake-only window; FE falls back to truncating
    # user_query when this is null.
    title: str | None = None
    domain_hint: str | None
    reference_urls: list[str]
    status: str
    target_roles: list[str]
    competitors: list[str]
    started_at: str
    finished_at: str | None
    created_at: str
    parent_run_id: str | None = None
    seed_competitor_ids: list[str] = Field(default_factory=list)
    # Phase 1b additions: derived from status + intake_draft + plan_tree so the FE
    # can render the live-run page without re-reading the LangGraph checkpoint.
    phase: Literal["intake", "planning", "executing", "done"] | None = None
    intake_draft: dict[str, object] | None = None
    plan_tree: dict[str, object] | None = None


class RunListItemResponse(BaseModel):
    run_id: str
    user_query: str
    title: str | None = None
    domain_hint: str | None
    status: str
    phase: Literal["intake", "planning", "executing", "done"] | None = None
    started_at: str
    finished_at: str | None
    created_at: str
    step_count: int
    evidence_count: int
    has_report: bool


class RunListResponse(BaseModel):
    items: list[RunListItemResponse]
    total: int
    limit: int
    offset: int


class StepTraceResponse(BaseModel):
    step_id: str
    run_id: str
    agent_name: str
    status: str
    retry_count: int
    payload: dict[str, object]
    rejection_reason: dict[str, object] | None
    started_at: str
    finished_at: str | None
    created_at: str


class SupervisorDecisionTraceResponse(BaseModel):
    id: str
    run_id: str
    iteration: int
    chosen_tool: str
    tool_args: dict[str, object]
    reasoning_summary: str
    triggered_by: str | None
    outcome: str | None
    outcome_recorded_at: str | None
    created_at: str


class LLMCallTraceResponse(BaseModel):
    id: int
    step_id: str
    model_slot: str
    provider: str | None
    model_name: str | None
    prompt_hash: str | None
    prompt_preview: str | None
    prompt_tokens: int | None
    completion_tokens: int | None
    latency_ms: int | None
    error: str | None
    retry_count: int
    fallback_used: bool | None
    fallback_reason: str | None
    created_at: str


class TraceTimelineItemResponse(BaseModel):
    kind: Literal["step", "decision", "llm_call"]
    timestamp: str
    step_id: str | None
    agent_name: str | None
    summary: str
    payload: dict[str, object]


class RunTraceResponse(BaseModel):
    run: RunDetailResponse
    steps: list[StepTraceResponse]
    supervisor_decisions: list[SupervisorDecisionTraceResponse]
    llm_calls: list[LLMCallTraceResponse]
    timeline: list[TraceTimelineItemResponse]


class EvidenceBriefResponse(BaseModel):
    evidence_id: str
    source_type: str
    source_url: str | None
    source_title: str | None
    competitor_id: str | None


class RunReportResponse(BaseModel):
    run_id: str
    status: str
    content_markdown: str
    content_json: dict[str, object]
    generated_at: str
    evidence_id_to_brief: dict[str, EvidenceBriefResponse]


class EvidenceListItemResponse(BaseModel):
    evidence_id: str
    run_id: str
    source_type: str
    source_url: str | None
    source_title: str | None
    quote: str
    sanitized_text: str
    source_language: str | None
    translated_excerpt: str | None
    competitor_id: str | None
    metadata: dict[str, object] | None
    desensitized: bool
    collected_at: str
    created_at: str


class CompetitorSeedResponse(BaseModel):
    id: str
    display_name: str
    aliases: list[str] = Field(default_factory=list)
    official_url: str | None = None
    category: str | None = None


class RunMetricsResponse(BaseModel):
    run_id: str
    coverage_rate: float
    evidence_count_total: int
    evidence_count_by_competitor: dict[str, int]
    evidence_count_by_dimension: dict[str, int]
    comparison_dimensions: list[str]
    conclusion_sections: list[str]
    report_section_ids: list[str]
    dimension_coverage_rate: float
    evidence_dimension_coverage_rate: float
    report_char_count: int
    report_section_count: int
    report_depth: str
    report_section_coverage_rate: float
    knowledge_feature_count: int
    knowledge_pricing_count: int
    knowledge_persona_count: int
    knowledge_schema_coverage_rate: float
    source_type_distribution: dict[str, int]
    source_authority_distribution: dict[str, int]
    locale_match_rate: float
    locale_distribution: dict[str, int]
    desensitization_coverage: float
    qa_total_steps: int
    qa_rejected_steps: int
    qa_rejection_rate: float
    supervisor_iterations: int
    llm_token_total: int
    llm_call_count: int
    llm_latency_p50_ms: int | None
    llm_provider_error_count: int
    llm_retry_total: int
    manual_review_rate: float
    manual_review_is_proxy: bool
    run_wall_clock_seconds: int | None
    evidence_floor_count: int = 0
    non_floor_grounded_count: int = 0


class ConclusionItemResponse(BaseModel):
    conclusion_id: str
    run_id: str
    step_id: str
    section: str
    claim: str
    confidence: str
    competitor_ids: list[str]
    risk_flags: list[str]
    evidence_ids: list[str]
    created_at: str


class RunConclusionsResponse(BaseModel):
    run_id: str
    items: list[ConclusionItemResponse]


class KnowledgeFeatureResponse(BaseModel):
    id: str
    competitor_id: str
    name: str
    parent_id: str | None = None
    description: str | None = None
    maturity: str | None = None
    evidence_ids: list[str]


class KnowledgePricingResponse(BaseModel):
    id: str
    competitor_id: str
    model: str
    tiers: list[dict[str, object]]
    free_plan: bool | None = None
    enterprise_plan: bool | None = None
    evidence_ids: list[str]


class KnowledgePersonaResponse(BaseModel):
    id: str
    competitor_id: str
    name: str
    role: str
    pain_points: list[str]
    jobs_to_be_done: list[str]
    evidence_ids: list[str]


class KnowledgeFeedbackResponse(BaseModel):
    id: str
    competitor_id: str
    sentiment: str
    topic: str
    summary: str
    evidence_ids: list[str]


class KnowledgeCompetitorResponse(BaseModel):
    competitor_id: str
    role: str | None = None
    segment: str | None = None
    vendor: str | None = None
    introduction: str | None = None


class RunKnowledgeResponse(BaseModel):
    run_id: str
    analysis_archetype: str
    schema_version: str
    competitors: list[KnowledgeCompetitorResponse]
    features: list[KnowledgeFeatureResponse]
    pricings: list[KnowledgePricingResponse]
    personas: list[KnowledgePersonaResponse]
    feedback: list[KnowledgeFeedbackResponse]
    missing_reasons: dict[str, list[str]]
    coverage: dict[str, object]


class ComparisonCellResponse(BaseModel):
    cell_id: str
    run_id: str
    step_id: str
    dimension: str
    competitor_id: str
    stance: str
    summary: str
    evidence_ids: list[str]
    created_at: str


class DimensionComparisonResponse(BaseModel):
    dimension: str
    cells: list[ComparisonCellResponse]


class RunComparisonsResponse(BaseModel):
    run_id: str
    items: list[DimensionComparisonResponse]


class WatchlistCreateRequest(BaseModel):
    competitor_id: str
    note: str | None = None
    next_refresh_at: datetime | None = None
    added_from_run_id: str | None = None
    source_role: str | None = None
    refresh_interval_hours: int | None = None

    @field_validator("competitor_id")
    @classmethod
    def _validate_competitor_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("competitor_id cannot be empty.")
        return normalized

    @field_validator("note")
    @classmethod
    def _normalize_note(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None

    @field_validator("added_from_run_id")
    @classmethod
    def _normalize_added_from_run_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None

    @field_validator("source_role")
    @classmethod
    def _normalize_source_role(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None

    @field_validator("refresh_interval_hours")
    @classmethod
    def _validate_refresh_interval(cls, value: int | None) -> int | None:
        if value is not None and value <= 0:
            raise ValueError("refresh_interval_hours must be a positive integer.")
        return value


class WatchlistUpdateRequest(BaseModel):
    note: str | None = None
    next_refresh_at: datetime | None = None
    refresh_interval_hours: int | None = None

    @field_validator("refresh_interval_hours")
    @classmethod
    def _validate_refresh_interval(cls, value: int | None) -> int | None:
        if value is not None and value <= 0:
            raise ValueError("refresh_interval_hours must be a positive integer.")
        return value


class WatchlistItemResponse(BaseModel):
    watch_id: str
    competitor_id: str
    note: str | None
    next_refresh_at: str | None
    added_from_run_id: str | None = None
    source_role: str | None = None
    last_refreshed_at: str | None = None
    refresh_interval_hours: int | None = None
    last_run_id: str | None = None
    created_at: str


class WatchInsightItemResponse(BaseModel):
    conclusion_id: str
    run_id: str
    run_title: str
    section: str
    claim: str
    confidence: str
    evidence_ids: list[str]
    created_at: str


class WatchlistDigestDeltaResponse(BaseModel):
    latest_run_id: str | None
    previous_run_id: str | None
    added_claims: list[str]
    removed_claims: list[str]


class CompetitorDiffItemResponse(BaseModel):
    diff_id: str
    competitor_id: str
    run_id_new: str
    run_id_old: str
    dimension: str
    change_type: str
    old_value: dict | None
    new_value: dict | None
    significance: str
    created_at: str


class WatchlistDigestItemResponse(BaseModel):
    watch_id: str
    competitor_id: str
    profile: KnowledgeCompetitorResponse | None = None
    note: str | None
    created_at: str
    insight_count: int
    run_count: int
    last_updated_at: str | None
    latest_run_id: str | None
    added_from_run_id: str | None = None
    source_role: str | None = None
    next_refresh_at: str | None = None
    delta: WatchlistDigestDeltaResponse | None = None
    last_run_id: str | None = None
    last_refreshed_at: str | None = None
    refresh_interval_hours: int | None = None
    items: list[WatchInsightItemResponse]
    recent_changes: list[CompetitorDiffItemResponse]


def _to_sse_chunk(*, event: str, data: dict[str, object]) -> str:
    serialized = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {serialized}\n\n"


def _event_bus_from_request(request: Request) -> EventBus | None:
    event_bus = getattr(request.app.state, "event_bus", None)
    if isinstance(event_bus, EventBus):
        return event_bus
    return None


def _register_background_task(request: Request, task: asyncio.Task[object]) -> None:
    background_tasks = getattr(request.app.state, "background_tasks", None)
    if not isinstance(background_tasks, set):
        return
    background_tasks.add(task)

    def _on_done(finished_task: asyncio.Task[object]) -> None:
        background_tasks.discard(finished_task)
        if finished_task.cancelled():
            return
        task_exc = finished_task.exception()
        if task_exc is not None:
            log.error(
                "api.background_task.failed",
                task_name=finished_task.get_name(),
                exc_type=type(task_exc).__name__,
                error=format_exception_for_log(task_exc),
            )

    task.add_done_callback(_on_done)


def _build_run_finish_payload(
    *,
    run_id: str,
    status: str,
    error_type: str | None = None,
    error_message: str | None = None,
) -> dict[str, object]:
    payload: dict[str, object] = {"run_id": run_id, "status": status}
    if error_type is not None:
        payload["error_type"] = error_type
    if error_message is not None:
        payload["error_message"] = error_message[:500]
    return payload


async def _mark_run_failed_and_emit(
    *,
    run_id: str,
    exc: BaseException,
    log_event: str,
) -> None:
    """Background-task boundary cleanup: persist run.status=failed + emit RUN_FINISH.

    Centralises the failure path so all three async graph runners stay in lockstep
    when a node throws an unexpected error. Without this the asyncio task dies
    silently ("Task exception was never retrieved") and the Run row stays
    "running" forever, leaving the UI polling against a corpse.
    """
    error_type = type(exc).__name__
    error_message = format_exception_for_log(exc)
    log.error(log_event, error_type=error_type, error=error_message)
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is not None:
            run.status = "failed"
            run.finished_at = datetime.now(timezone.utc)
            await session.commit()
    await _settle_run_billing(run_id=run_id, terminal_status="failed")
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.RUN_FINISH,
        payload=_build_run_finish_payload(
            run_id=run_id,
            status="failed",
            error_type=error_type,
            error_message=error_message,
        ),
    )


_RUN_TASK_NAME_PREFIXES: tuple[str, ...] = (
    "run_graph_",
    "intake_resume_",
    "plan_resume_",
)


async def _handle_graph_cancelled(*, run_id: str, log_event: str) -> None:
    """Reconcile the Run row when a graph task receives CancelledError.

    Two ways this fires:
      1. User cancel via PATCH /runs — the endpoint already flipped the row to
         "cancelled" and emitted RUN_FINISH, so this branch is a no-op.
      2. Unexpected cancellation (e.g. lifespan shutdown asking tasks to stop) —
         the row is still "running"; mark it failed so the UI doesn't hang.
    The caller MUST re-raise CancelledError per asyncio's cooperative-cancel
    contract; failing to do so makes the task look like a normal completion.
    """
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        if run.status != "running":
            with bind_run(run_id):
                log.info(log_event, status=run.status, branch="already_terminal")
            await _settle_run_billing(run_id=run_id, terminal_status=str(run.status))
            return
        run.status = "failed"
        run.finished_at = datetime.now(timezone.utc)
        await session.commit()
    await _settle_run_billing(run_id=run_id, terminal_status="failed")
    with bind_run(run_id):
        log.warning(log_event, status="failed", branch="unexpected_cancel")
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.RUN_FINISH,
        payload=_build_run_finish_payload(
            run_id=run_id,
            status="failed",
            error_type="CancelledError",
            error_message="后台任务被中止（可能是服务重启）",
        ),
    )


def _cancel_background_tasks_for_run(
    *,
    background_tasks: set[asyncio.Task[object]] | None,
    run_id: str,
) -> int:
    """Cancel any in-flight graph tasks bound to this run.

    Tasks are named with the run_id suffix on creation (see `name=f"..._{run_id}"`
    in this module). We don't cancel the skill_curator follow-up — it only fires
    on terminal completion, so a cancel during execution means there's no curator
    task to chase yet. CancelledError propagates back through the outer boundary
    (`_mark_run_failed_and_emit`), which would normally flip the row to failed;
    PATCH /runs caller flips it to "cancelled" first so the user's intent wins.
    """
    if not isinstance(background_tasks, set):
        return 0
    cancelled = 0
    for task in list(background_tasks):
        name = task.get_name()
        if not any(name.startswith(prefix) and name.endswith(run_id) for prefix in _RUN_TASK_NAME_PREFIXES):
            continue
        if task.done():
            continue
        task.cancel()
        cancelled += 1
    return cancelled


def _coerce_run_status(state: object) -> str:
    if isinstance(state, dict):
        status_raw = state.get("status", "completed")
    else:
        status_raw = "completed"
    status = str(status_raw)
    if status in {"completed", "degraded"}:
        return status
    return "completed"


def _has_checkpoint_state(values: object) -> bool:
    if not isinstance(values, dict):
        return False
    return bool(values)


def _build_reset_state_values(*, reset_to: ResetToStage) -> dict[str, object]:
    values: dict[str, object] = {
        "pending_tool_args": {},
        "pending_review_target_step_id": None,
        "last_completed_node": None,
        "qa_outcome": None,
        "qa_reject_to": None,
        "qa_rejection_count": 0,
        "qa_reasons": [],
        "status": "running",
        "decisions": [],
    }
    if reset_to == "writer":
        values["next_action"] = "writer"
        values["report_draft_done"] = False
        values["pending_tool_args"] = {
            "template_id": None,
            "sections": [*DEFAULT_FOCUS_DIMENSIONS, "differentiation"],
        }
        return values

    values["next_action"] = "analyst"
    values["analysis_done"] = False
    values["report_draft_done"] = False
    values["pending_tool_args"] = {
        "focus_dimensions": [*DEFAULT_FOCUS_DIMENSIONS, "positioning"],
        "parallel_by_dimension": False,
        "require_cross_competitor": True,
    }
    return values


async def _cleanup_trace_for_reset(
    *,
    run_id: str,
    reset_to: ResetToStage,
) -> None:
    session_factory = get_session_factory()
    agent_names = RESET_STAGE_AGENT_NAMES[reset_to]
    decision_tools = RESET_STAGE_DECISION_TOOLS[reset_to]
    async with session_factory() as session:
        # NOTE: reset_to replay is an explicit exception to append-only trace:
        # we intentionally remove replay-target stages and their downstream data.
        if reset_to == "analyst":
            await session.execute(
                delete(ConclusionRecord).where(ConclusionRecord.run_id == run_id)
            )
        await session.execute(delete(Report).where(Report.run_id == run_id))
        await session.execute(
            delete(Step).where(
                Step.run_id == run_id,
                Step.agent_name.in_(agent_names),
            )
        )
        await session.execute(
            delete(SupervisorDecisionRecord).where(
                SupervisorDecisionRecord.run_id == run_id,
                SupervisorDecisionRecord.chosen_tool.in_(decision_tools),
            )
        )
        await session.commit()


async def _run_event_stream(
    *,
    event_bus: EventBus,
    run_id: str,
    keepalive_seconds: float = 15.0,
    max_events: int | None = None,
) -> AsyncIterator[str]:
    yield "retry: 15000\n\n"
    emitted_count = 0
    async with event_bus.subscribe(run_id) as queue:
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=keepalive_seconds)
            except asyncio.TimeoutError:
                yield ": keepalive\n\n"
                continue
            except asyncio.CancelledError:
                return
            yield _to_sse_chunk(
                event=event.event_type.value,
                data=event.model_dump(mode="json"),
            )
            emitted_count += 1
            if max_events is not None and emitted_count >= max_events:
                return


def _to_iso(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    return dt.isoformat()


def _derive_run_phase(run: Run) -> Literal["intake", "planning", "executing", "done"] | None:
    """Phase is a derived view, not stored — the source of truth is the LangGraph state.

    Legacy runs (created via POST /api/runs without intake) have `intake_draft is None`
    and return `None` here so the FE renders them with the old layout.

    Phase 2: `plan_tree.confirmed_at` is the planning→executing signal. planner_generate
    writes `confirmed_at=None`; planner_wait sets it on resume.
    """
    intake_draft = run.intake_draft
    if intake_draft is None:
        return None
    if run.status in {"completed", "degraded", "failed"}:
        return "done"
    plan_tree = run.plan_tree
    if plan_tree is not None:
        if plan_tree.get("confirmed_at") is not None:
            return "executing"
        return "planning"
    intake_complete = bool(intake_draft.get("user_role")) and bool(
        intake_draft.get("analysis_intent")
    ) and (
        bool(intake_draft.get("competitors_explicit"))
        or bool(intake_draft.get("competitors_discovery_mode"))
    )
    if not intake_complete:
        return "intake"
    return "planning"


def _to_run_detail(run: Run) -> RunDetailResponse:
    return RunDetailResponse(
        run_id=run.run_id,
        owner_user_id=run.owner_user_id,
        reservation_id=run.reservation_id,
        reserved_micro_points=int(run.reserved_micro_points or 0),
        consumed_micro_points=int(run.consumed_micro_points or 0),
        billing_status=run.billing_status,
        user_query=run.user_query,
        title=run.title,
        domain_hint=run.domain_hint if run.domain_hint else None,
        reference_urls=list(run.reference_urls or []),
        status=run.status,
        target_roles=list(run.target_roles),
        competitors=list(run.competitors),
        started_at=run.started_at.isoformat(),
        finished_at=_to_iso(run.finished_at),
        created_at=run.created_at.isoformat(),
        parent_run_id=run.parent_run_id,
        seed_competitor_ids=list(run.seed_competitor_ids or []),
        phase=_derive_run_phase(run),
        intake_draft=dict(run.intake_draft) if run.intake_draft is not None else None,
        plan_tree=dict(run.plan_tree) if run.plan_tree is not None else None,
    )


def _to_step_trace_response(step: Step) -> StepTraceResponse:
    return StepTraceResponse(
        step_id=step.step_id,
        run_id=step.run_id,
        agent_name=step.agent_name,
        status=step.status,
        retry_count=step.retry_count,
        payload=step.payload,
        rejection_reason=step.rejection_reason,
        started_at=step.started_at.isoformat(),
        finished_at=_to_iso(step.finished_at),
        created_at=step.created_at.isoformat(),
    )


def _to_supervisor_decision_trace_response(
    decision: SupervisorDecisionRecord,
) -> SupervisorDecisionTraceResponse:
    return SupervisorDecisionTraceResponse(
        id=decision.id,
        run_id=decision.run_id,
        iteration=decision.iteration,
        chosen_tool=decision.chosen_tool,
        tool_args=decision.tool_args,
        reasoning_summary=decision.reasoning_summary,
        triggered_by=decision.triggered_by,
        outcome=decision.outcome,
        outcome_recorded_at=_to_iso(decision.outcome_recorded_at),
        created_at=decision.created_at.isoformat(),
    )


def _to_llm_call_trace_response(llm_call: LLMCall) -> LLMCallTraceResponse:
    return LLMCallTraceResponse(
        id=llm_call.id,
        step_id=llm_call.step_id,
        model_slot=llm_call.model_slot,
        provider=llm_call.provider,
        model_name=llm_call.model_name,
        prompt_hash=llm_call.prompt_hash,
        prompt_preview=llm_call.prompt_preview,
        prompt_tokens=llm_call.prompt_tokens,
        completion_tokens=llm_call.completion_tokens,
        latency_ms=llm_call.latency_ms,
        error=llm_call.error,
        retry_count=llm_call.retry_count,
        fallback_used=llm_call.fallback_used,
        fallback_reason=llm_call.fallback_reason,
        created_at=llm_call.created_at.isoformat(),
    )


def _build_trace_timeline(
    *,
    step_rows: list[Step],
    decision_rows: list[SupervisorDecisionRecord],
    llm_rows: list[LLMCall],
) -> list[TraceTimelineItemResponse]:
    timeline_rows: list[tuple[datetime, int, TraceTimelineItemResponse]] = []
    step_agent_by_id = {step.step_id: step.agent_name for step in step_rows}

    for step in step_rows:
        timeline_rows.append(
            (
                step.created_at,
                0,
                TraceTimelineItemResponse(
                    kind="step",
                    timestamp=step.created_at.isoformat(),
                    step_id=step.step_id,
                    agent_name=step.agent_name,
                    summary=f"{step.agent_name} {step.status}",
                    payload={
                        "status": step.status,
                        "retry_count": step.retry_count,
                        "started_at": step.started_at.isoformat(),
                        "finished_at": _to_iso(step.finished_at),
                    },
                ),
            )
        )

    for decision in decision_rows:
        summary_parts = [decision.chosen_tool]
        if decision.outcome:
            summary_parts.append(decision.outcome)
        timeline_rows.append(
            (
                decision.created_at,
                1,
                TraceTimelineItemResponse(
                    kind="decision",
                    timestamp=decision.created_at.isoformat(),
                    step_id=None,
                    agent_name="supervisor",
                    summary=" ".join(summary_parts),
                    payload={
                        "decision_id": decision.id,
                        "iteration": decision.iteration,
                        "chosen_tool": decision.chosen_tool,
                        "triggered_by": decision.triggered_by,
                        "outcome": decision.outcome,
                    },
                ),
            )
        )

    for llm_call in llm_rows:
        provider_label = llm_call.provider or "unknown_provider"
        timeline_rows.append(
            (
                llm_call.created_at,
                2,
                TraceTimelineItemResponse(
                    kind="llm_call",
                    timestamp=llm_call.created_at.isoformat(),
                    step_id=llm_call.step_id,
                    agent_name=step_agent_by_id.get(llm_call.step_id),
                    summary=f"{llm_call.model_slot} {provider_label}",
                    payload={
                        "llm_call_id": llm_call.id,
                        "model_slot": llm_call.model_slot,
                        "provider": llm_call.provider,
                        "model_name": llm_call.model_name,
                        "prompt_hash": llm_call.prompt_hash,
                        "prompt_preview": llm_call.prompt_preview,
                        "prompt_tokens": llm_call.prompt_tokens,
                        "completion_tokens": llm_call.completion_tokens,
                        "latency_ms": llm_call.latency_ms,
                        "error": llm_call.error,
                        "retry_count": llm_call.retry_count,
                        "fallback_used": llm_call.fallback_used,
                        "fallback_reason": llm_call.fallback_reason,
                    },
                ),
            )
        )

    timeline_rows.sort(key=lambda row: (row[0], row[1]))
    return [item for _, _, item in timeline_rows]


def _build_run_summary_fields(
    *,
    snapshot: RunMetricsSnapshot,
    status: str,
) -> dict[str, object]:
    return {
        "status": status,
        "run_wall_clock_seconds": snapshot.run_wall_clock_seconds,
        "llm_call_count": snapshot.llm_call_count,
        "llm_token_total": snapshot.llm_token_total,
        "llm_latency_p50_ms": snapshot.llm_latency_p50_ms,
        "coverage_rate": snapshot.coverage_rate,
        "evidence_count_total": snapshot.evidence_count_total,
        "qa_rejection_rate": snapshot.qa_rejection_rate,
        "supervisor_iterations": snapshot.supervisor_iterations,
    }


def _to_watchlist_item(item: WatchlistItem) -> WatchlistItemResponse:
    return WatchlistItemResponse(
        watch_id=item.watch_id,
        competitor_id=item.competitor_id,
        note=item.note,
        next_refresh_at=_to_iso(item.next_refresh_at),
        added_from_run_id=item.added_from_run_id,
        source_role=item.source_role,
        last_refreshed_at=_to_iso(item.last_refreshed_at),
        refresh_interval_hours=item.refresh_interval_hours,
        last_run_id=item.last_run_id,
        created_at=item.created_at.isoformat(),
    )


def _watchlist_delta_from_insights(
    insights: list[WatchInsightItemResponse],
) -> WatchlistDigestDeltaResponse | None:
    claims_by_run_id: dict[str, set[str]] = {}
    ordered_run_ids: list[str] = []
    for insight in insights:
        if insight.run_id not in claims_by_run_id:
            claims_by_run_id[insight.run_id] = set()
            ordered_run_ids.append(insight.run_id)
        claim = insight.claim.strip()
        if claim:
            claims_by_run_id[insight.run_id].add(claim)
    if len(ordered_run_ids) < 2:
        return None
    latest_run_id = ordered_run_ids[0]
    previous_run_id = ordered_run_ids[1]
    latest_claims = claims_by_run_id.get(latest_run_id, set())
    previous_claims = claims_by_run_id.get(previous_run_id, set())
    return WatchlistDigestDeltaResponse(
        latest_run_id=latest_run_id,
        previous_run_id=previous_run_id,
        added_claims=sorted(latest_claims - previous_claims)[:5],
        removed_claims=sorted(previous_claims - latest_claims)[:5],
    )


def _normalize_competitor_key(value: str) -> str:
    lowered = value.casefold().strip()
    if not lowered:
        return ""
    normalized = re.sub(r"[^\w\s\u4e00-\u9fff]+", " ", lowered, flags=re.UNICODE)
    tokens = [token for token in normalized.split() if token]
    if not tokens:
        return ""
    # Token sorting absorbs punctuation and token-order variants
    # ("Meta Ray-Ban" vs "Ray-Ban Meta").
    return " ".join(sorted(tokens))


@lru_cache
def _competitor_seed_alias_sets() -> tuple[frozenset[str], ...]:
    alias_sets: list[frozenset[str]] = []
    for row in _load_competitor_seed_rows():
        raw_values: list[str] = []
        row_id = row.get("id")
        if isinstance(row_id, str) and row_id.strip():
            raw_values.append(row_id)
        display_name = row.get("display_name")
        if isinstance(display_name, str) and display_name.strip():
            raw_values.append(display_name)
        aliases = row.get("aliases")
        if isinstance(aliases, list):
            for alias in aliases:
                if isinstance(alias, str) and alias.strip():
                    raw_values.append(alias)
        normalized_keys = {
            _normalize_competitor_key(raw_value)
            for raw_value in raw_values
            if _normalize_competitor_key(raw_value)
        }
        if normalized_keys:
            alias_sets.append(frozenset(normalized_keys))
    return tuple(alias_sets)


def _alias_keys_for_competitor(value: str) -> set[str]:
    normalized = _normalize_competitor_key(value)
    if not normalized:
        return set()
    alias_keys = {normalized}
    for alias_set in _competitor_seed_alias_sets():
        if normalized in alias_set:
            alias_keys.update(alias_set)
    return alias_keys


def _resolve_run_title(*, title: str | None, user_query: str) -> str:
    if isinstance(title, str):
        normalized = title.strip()
        if normalized:
            return normalized
    return user_query


def _extract_competitor_id(span: dict[str, object] | None) -> str | None:
    if not isinstance(span, dict):
        return None
    competitor_id = span.get("competitor_id")
    return competitor_id if isinstance(competitor_id, str) else None


def _extract_source_language(span: dict[str, object] | None) -> str | None:
    if not isinstance(span, dict):
        return None
    for key in ("source_language", "detected_language"):
        value = span.get(key)
        if isinstance(value, str):
            normalized = value.strip()
            if normalized:
                return normalized
    return None


def _extract_translated_excerpt(span: dict[str, object] | None) -> str | None:
    if not isinstance(span, dict):
        return None
    translated_raw = span.get("translated_excerpt")
    if not isinstance(translated_raw, str):
        return None
    translated_excerpt = translated_raw.strip()
    return translated_excerpt if translated_excerpt else None


def _normalize_competitor_inputs(values: list[str]) -> list[str]:
    normalized: list[str] = []
    seen: set[str] = set()
    for raw in values:
        value = raw.strip()
        if not value:
            continue
        if value in seen:
            continue
        seen.add(value)
        normalized.append(value)
    return normalized


def _competitor_seed_file_path() -> Path:
    if settings.DEMO_FIXTURES_DIR:
        return Path(settings.DEMO_FIXTURES_DIR) / "competitors_seed.yaml"
    docker_mount = Path("/demo_fixtures/competitors_seed.yaml")
    if docker_mount.exists():
        return docker_mount
    # backend/app/router/run_rt.py -> backend/demo_fixtures
    return Path(__file__).resolve().parents[2] / "demo_fixtures" / "competitors_seed.yaml"


def _load_competitor_seed_rows() -> list[dict[str, object]]:
    path = _competitor_seed_file_path()
    if not path.exists():
        return []
    try:
        loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return []
    competitors_raw: object
    if isinstance(loaded, list):
        competitors_raw = loaded
    elif isinstance(loaded, dict):
        competitors_raw = loaded.get("competitors")
    else:
        return []
    if not isinstance(competitors_raw, list):
        return []
    rows: list[dict[str, object]] = []
    for item in competitors_raw:
        if isinstance(item, dict):
            rows.append(item)
    return rows


def _validate_competitors(payload: RunCreateRequest) -> list[str]:
    """Normalize competitor inputs. Empty list is allowed (discovery mode)."""
    return _normalize_competitor_inputs(payload.competitors)


@router.get("/api/runs", response_model=RunListResponse)
async def list_runs(
    status: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> RunListResponse:
    normalized_status = status.strip() if isinstance(status, str) else None
    session_factory = get_session_factory()
    identity = get_identity()
    async with session_factory() as session:
        step_count_subquery = (
            select(func.count())
            .select_from(Step)
            .where(Step.run_id == Run.run_id)
            .scalar_subquery()
        )
        evidence_count_subquery = (
            select(func.count())
            .select_from(EvidenceRecord)
            .where(EvidenceRecord.run_id == Run.run_id)
            .scalar_subquery()
        )
        report_count_subquery = (
            select(func.count())
            .select_from(Report)
            .where(Report.run_id == Run.run_id)
            .scalar_subquery()
        )
        list_query = select(
            Run,
            step_count_subquery.label("step_count"),
            evidence_count_subquery.label("evidence_count"),
            report_count_subquery.label("report_count"),
        )
        total_query = select(func.count()).select_from(Run)
        if identity.user_id != 0:
            list_query = list_query.where(Run.owner_user_id == identity.user_id)
            total_query = total_query.where(Run.owner_user_id == identity.user_id)
        if normalized_status:
            list_query = list_query.where(Run.status == normalized_status)
            total_query = total_query.where(Run.status == normalized_status)
        list_query = list_query.order_by(Run.started_at.desc()).limit(limit).offset(offset)
        rows = (await session.execute(list_query)).all()
        total = int((await session.execute(total_query)).scalar_one())

    items: list[RunListItemResponse] = []
    for run, step_count, evidence_count, report_count in rows:
        items.append(
            RunListItemResponse(
                run_id=run.run_id,
                user_query=run.user_query,
                title=run.title,
                domain_hint=run.domain_hint if run.domain_hint else None,
                status=run.status,
                phase=_derive_run_phase(run),
                started_at=run.started_at.isoformat(),
                finished_at=_to_iso(run.finished_at),
                created_at=run.created_at.isoformat(),
                step_count=int(step_count),
                evidence_count=int(evidence_count),
                has_report=int(report_count) > 0,
            )
        )
    return RunListResponse(
        items=items,
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get("/api/demo-fixtures/competitors", response_model=list[CompetitorSeedResponse])
async def list_competitor_seeds() -> list[CompetitorSeedResponse]:
    return [CompetitorSeedResponse.model_validate(item) for item in _load_competitor_seed_rows()]


async def _execute_run_graph(
    *,
    run_id: str,
    graph: Any,
    initial_state: dict[str, object],
    domain_hint: str | None,
    recursion_limit: int,
    background_tasks: set[asyncio.Task[object]],
) -> None:
    """Run the supervisor graph to completion off the request path (Phase 0b async).

    Mirrors the skill-curator background-task pattern: catch the boundary error
    families, persist terminal status, emit run.finish, and return. Unknown errors
    propagate to asyncio so they remain visible instead of being silently hidden.
    """
    session_factory = get_session_factory()
    config = {"configurable": {"thread_id": run_id}, "recursion_limit": recursion_limit}
    with bind_run(run_id):
        try:
            graph_state = await _run_graph_with_progress_heartbeat(
                run_id=run_id,
                phase="execute",
                graph=graph,
                config=config,
                invoke_coro=graph.ainvoke(initial_state, config=config),
            )
        except asyncio.CancelledError:
            await _handle_graph_cancelled(
                run_id=run_id, log_event="api.run.execute.cancelled"
            )
            raise
        except Exception as exc:
            # Background-task outer boundary: persist failed + RUN_FINISH; do not re-raise
            # or asyncio emits unstructured "Task exception was never retrieved" noise.
            await _mark_run_failed_and_emit(
                run_id=run_id, exc=exc, log_event="api.run.execute.failed"
            )
            return

        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise RuntimeError(f"run_id={run_id} should exist after creation")
            run_status = str(graph_state.get("status", "completed"))
            run.status = run_status if run_status in {"completed", "degraded"} else "completed"
            run.finished_at = datetime.now(timezone.utc)
            final_competitors = graph_state.get("competitors")
            if isinstance(final_competitors, list) and final_competitors:
                run.competitors = final_competitors
            await session.commit()
            final_status = run.status
        await _settle_run_billing(run_id=run_id, terminal_status=final_status)
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(run_id=run_id, status=final_status),
        )
        await _log_run_summary(run_id=run_id, status=final_status)
        diff_task = asyncio.create_task(
            _compute_and_persist_diffs(run_id=run_id),
            name=f"competitor_diff_{run_id}",
        )
        background_tasks.add(diff_task)
        diff_task.add_done_callback(background_tasks.discard)
        curator_task = asyncio.create_task(
            run_skill_curator_for_run(run_id=run_id, domain_hint=domain_hint),
            name=f"skill_curator_{run_id}",
        )
        background_tasks.add(curator_task)
        curator_task.add_done_callback(background_tasks.discard)
        log.info("api.run.execute.finish", status=final_status)


async def _settle_run_billing(*, run_id: str, terminal_status: str) -> None:
    """Close a Member reservation once and persist the usage snapshot."""
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None or run.billing_status == "SETTLED":
            return
        llm_rows = (
            await session.execute(
                select(LLMCall)
                .join(Step, LLMCall.step_id == Step.step_id)
                .where(Step.run_id == run_id)
            )
        ).scalars().all()
        # Supervisor state gates and QA guardrails also emit trace-only pseudo
        # records.  They intentionally have no token usage and must not keep a
        # real Member reservation in pending reconciliation.  Only records with
        # a non-pseudo prompt hash represent an actual provider call.
        billable_llm_rows = [row for row in llm_rows if row.prompt_hash != "pseudo_response"]
        actual = sum(max(0, int(row.charged_micro_points or 0)) for row in billable_llm_rows)
        unknown_usage_count = sum(
            1
            for row in billable_llm_rows
            if not row.error
            and (row.prompt_tokens is None or row.completion_tokens is None)
        )
        reservation = Reservation(
            reservation_id=run.reservation_id or run_id,
            amount_micro_points=max(0, int(run.reserved_micro_points or 0)),
            request_id=f"agent:{run_id}",
            user_id=int(run.owner_user_id or 0),
        )
        if unknown_usage_count:
            # A provider that omits usage must never be charged from an estimate.
            # Keep the reservation open for a later reconciliation job instead of
            # silently confirming zero or a partial amount.
            run.consumed_micro_points = actual
            run.billing_status = "PENDING_RECONCILIATION"
            run.billing_error = (
                f"{unknown_usage_count} successful LLM call(s) did not return token usage"
            )
            await session.commit()
            log.warning(
                "billing.usage_missing",
                run_id=run_id,
                status=terminal_status,
                unknown_usage_count=unknown_usage_count,
            )
            return
        try:
            await quota_client.confirm(reservation, actual_micro_points=actual, trace_id=run_id)
            run.consumed_micro_points = actual
            run.billing_status = "SETTLED"
            run.billing_error = None
        except Exception as exc:
            run.consumed_micro_points = actual
            run.billing_status = "PENDING_RECONCILIATION"
            run.billing_error = str(exc)[:2000]
            log.warning(
                "billing.settlement.pending",
                run_id=run_id,
                status=terminal_status,
                error=str(exc),
            )
        await session.commit()


async def _compute_and_persist_diffs(*, run_id: str) -> None:
    """Compute competitive diffs for all watchlisted competitors in this run."""
    session_factory = get_session_factory()
    try:
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None or not isinstance(run.competitors, list):
                return
            watchlist_ids = {
                row[0]
                for row in (
                    await session.execute(
                        select(WatchlistItem.competitor_id)
                    )
                ).all()
            }
            competitors_to_diff = [c for c in run.competitors if c in watchlist_ids]

        for competitor_id in competitors_to_diff:
            try:
                async with session_factory() as session:
                    diffs = await compute_diff(
                        run_id_new=run_id,
                        competitor_id=competitor_id,
                        session=session,
                    )
                    if diffs:
                        session.add_all(diffs)
                        await session.commit()
                        log.info(
                            "diff.persisted",
                            run_id=run_id,
                            competitor_id=competitor_id,
                            count=len(diffs),
                        )
            except Exception as exc:
                log.warning(
                    "diff.error",
                    run_id=run_id,
                    competitor_id=competitor_id,
                    error=format_exception_for_log(exc),
                )
    except Exception as exc:
        log.warning("diff.batch.error", run_id=run_id, error=format_exception_for_log(exc))


async def _log_run_summary(*, run_id: str, status: str) -> None:
    session_factory = get_session_factory()
    try:
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                log.warning(
                    "api.run.summary.failed",
                    reason="run_not_found",
                )
                return

            evidence_rows = (
                await session.execute(
                    select(EvidenceRecord)
                    .where(EvidenceRecord.run_id == run_id)
                    .order_by(EvidenceRecord.created_at.asc())
                )
            ).scalars().all()
            step_rows = (
                await session.execute(
                    select(Step)
                    .where(Step.run_id == run_id)
                    .order_by(Step.created_at.asc())
                )
            ).scalars().all()
            llm_rows = (
                await session.execute(
                    select(LLMCall)
                    .join(Step, LLMCall.step_id == Step.step_id)
                    .where(Step.run_id == run_id)
                    .order_by(LLMCall.created_at.asc())
                )
            ).scalars().all()
            decision_rows = (
                await session.execute(
                    select(SupervisorDecisionRecord)
                    .where(SupervisorDecisionRecord.run_id == run_id)
                    .order_by(SupervisorDecisionRecord.created_at.asc())
                )
            ).scalars().all()
            report_rows = (
                await session.execute(
                    select(Report)
                    .where(Report.run_id == run_id)
                    .order_by(Report.created_at.asc())
                )
            ).scalars().all()
            candidate_rows = (
                await session.execute(select(SkillCandidateRecord))
            ).scalars().all()
            candidate_rows = [
                row
                for row in candidate_rows
                if run_id
                in (row.supporting_run_ids if isinstance(row.supporting_run_ids, list) else [])
            ]

        snapshot = build_run_metrics_snapshot(
            run=run,
            evidence_rows=list(evidence_rows),
            step_rows=list(step_rows),
            llm_rows=list(llm_rows),
            decision_rows=list(decision_rows),
            candidate_rows=list(candidate_rows),
            report_rows=list(report_rows),
        )
        log.info(
            "api.run.summary",
            **_build_run_summary_fields(snapshot=snapshot, status=status),
        )
    except (SQLAlchemyError, TypeError, ValueError, AttributeError) as exc:
        log.warning(
            "api.run.summary.failed",
            error=format_exception_for_log(exc),
        )


@router.post("/api/runs", response_model=RunCreateResponse)
async def create_run(payload: RunCreateRequest, request: Request) -> RunCreateResponse:
    normalized_competitors = _validate_competitors(payload)
    normalized_reference_urls = list(payload.reference_urls or [])
    run_id = make_id("run_")
    identity = get_identity()
    reservation_amount = default_reservation_amount(payload.max_micro_points)
    try:
        reservation = await quota_client.reserve(
            user_id=identity.user_id,
            amount_micro_points=reservation_amount,
            run_id=run_id,
            trace_id=run_id,
        )
    except Exception as exc:
        raise APIException(
            status_code=402,
            error_code="QUOTA_RESERVATION_FAILED",
            message="积分不足或积分服务暂不可用，请稍后重试。",
        ) from exc
    session_factory = get_session_factory()
    with bind_run(run_id):
        log.info(
            "api.run.create.start",
            domain_hint=payload.domain_hint,
            reference_url_count=len(normalized_reference_urls),
            competitor_count=len(normalized_competitors),
            target_role_count=len(payload.target_roles),
        )

        graph = getattr(request.app.state, "compiled_graph", None)
        if graph is None:
            raise APIException(
                status_code=500,
                error_code="GRAPH_NOT_INITIALIZED",
                message="Compiled LangGraph instance is not initialized.",
            )
        background_tasks = getattr(request.app.state, "background_tasks", None)
        if not isinstance(background_tasks, set):
            raise APIException(
                status_code=500,
                error_code="BACKGROUND_TASKS_NOT_INITIALIZED",
                message="Background task registry is not initialized.",
            )
        direct_user_role = (
            payload.target_roles[0]
            if payload.target_roles
            and payload.target_roles[0] in {"pm", "founder", "sales", "investor"}
            else None
        )
        direct_analysis_archetype = (
            "landscape" if payload.self_product is not None or not normalized_competitors else "comparison"
        )
        direct_intake_draft = RunIntakeDraft(
            user_query=payload.user_query,
            user_role=direct_user_role,
            analysis_intent=payload.user_query,
            competitors_explicit=normalized_competitors,
            competitors_discovery_mode=not normalized_competitors,
            domain_hint=payload.domain_hint,
            focus_dimensions=list(DEFAULT_FOCUS_DIMENSIONS),
            report_depth=payload.report_depth,
            reference_urls=normalized_reference_urls,
            self_product=payload.self_product,
            market_scope=payload.market_scope,
            time_context=payload.time_context,
            target_category=payload.target_category,
            category_aliases=payload.category_aliases,
            excluded_categories=payload.excluded_categories,
            market_segments=payload.market_segments,
            analysis_archetype=direct_analysis_archetype,
            response_language=resolve_report_language(
                response_language=payload.response_language,
                user_query=payload.user_query,
            ),
        )

        async with session_factory() as session:
            session.add(
                Run(
                    run_id=run_id,
                    owner_user_id=identity.user_id,
                    user_query=payload.user_query,
                    domain_hint=payload.domain_hint,
                    reference_urls=normalized_reference_urls,
                    status="running",
                    target_roles=payload.target_roles,
                    competitors=normalized_competitors,
                    intake_draft=direct_intake_draft.model_dump(exclude={"is_complete"}),
                    reservation_id=reservation.reservation_id,
                    reserved_micro_points=reservation.amount_micro_points,
                    billing_status="RESERVED",
                )
            )
            await session.commit()

        initial_state: dict[str, object] = {
            "run_id": run_id,
            "owner_user_id": identity.user_id,
            "domain_hint": payload.domain_hint,
            "market_scope": direct_intake_draft.market_scope,
            "response_language": direct_intake_draft.response_language,
            "reference_urls": normalized_reference_urls,
            "competitors": normalized_competitors,
            "discovered_competitors": [],
            "discovered_competitor_sources": {},
            "user_query": payload.user_query,
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "replan_count": 0,
            "current_iteration": 0,
            "pending_tool_args": {},
            "qa_outcome": None,
            "qa_reject_to": None,
            "qa_rejection_count": 0,
            "pending_review_target_step_id": None,
            "qa_reasons": [],
            "intake_draft": direct_intake_draft,
            "status": "running",
        }
        execution_config = _graph_invoke_config(
            run_id=run_id,
            report_depth=payload.report_depth,
        )
        task = asyncio.create_task(
            _execute_run_graph(
                run_id=run_id,
                graph=graph,
                initial_state=initial_state,
                domain_hint=payload.domain_hint,
                recursion_limit=int(execution_config["recursion_limit"]),
                background_tasks=background_tasks,
            ),
            name=f"run_graph_{run_id}",
        )
        _register_background_task(request, task)
        log.info("api.run.create.accepted")

    return RunCreateResponse(
        run_id=run_id,
        status="running",
        message="Run accepted; supervisor loop executing in background.",
        reserved_micro_points=reservation.amount_micro_points,
    )


# --- Phase 1b Agent-native intake (chat mode). Expert mode and plan/confirm
# are intentionally NOT implemented yet — see Phase 2 in the plan doc. ---


def _extract_first_interrupt_value(snapshot: Any) -> Any:
    """Canonical interrupt-payload extraction for langgraph 0.2.50 (Invariant D)."""
    for task in snapshot.tasks:
        if task.interrupts:
            return task.interrupts[0].value
    return None


def _coerce_intake_draft_from_state(state_values: dict[str, object]) -> RunIntakeDraft | None:
    raw = state_values.get("intake_draft")
    if isinstance(raw, RunIntakeDraft):
        return raw
    if isinstance(raw, dict):
        return RunIntakeDraft.model_validate(raw)
    return None


def _normalize_idempotency_key(
    *,
    header_value: str | None,
    body_value: str | None,
) -> str:
    """Resolve create-request idempotency key with strict precedence.

    Header key is preferred so upstream gateways / SDK retries can inject it
    without mutating JSON payloads. Falls back to body field for clients that
    cannot set custom headers.
    """
    header_key = header_value.strip() if isinstance(header_value, str) else ""
    if header_key:
        return header_key
    body_key = body_value.strip() if isinstance(body_value, str) else ""
    if body_key:
        return body_key
    return f"idemp_{uuid4().hex}"


def _intake_request_fingerprint(payload: IntakeCreateRequest) -> str:
    """Stable hash for idempotency conflict detection (same key, different body)."""
    canonical: dict[str, object] = {
        "user_query": payload.user_query.strip(),
        "user_role": payload.user_role,
        "domain_hint": payload.domain_hint,
        "reference_urls": list(payload.reference_urls or []),
        "competitors_explicit": list(payload.competitors_explicit),
        "competitors_discovery_mode": payload.competitors_discovery_mode,
        "focus_dimensions": list(payload.focus_dimensions),
        "report_depth": payload.report_depth,
        "from_run_id": payload.from_run_id,
        "seed_competitor_ids": list(payload.seed_competitor_ids),
        "target_category": payload.target_category,
        "category_aliases": list(payload.category_aliases),
        "excluded_categories": list(payload.excluded_categories),
        "market_segments": list(payload.market_segments),
    }
    encoded = json.dumps(canonical, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


async def _persist_intake_draft_to_run(
    *,
    run_id: str,
    state_values: dict[str, object],
) -> None:
    """Snapshot the latest intake_draft from graph state into the Run row.

    Allows GET /api/runs/{id} to render the current intake state without
    re-reading the LangGraph checkpoint.
    """
    draft = _coerce_intake_draft_from_state(state_values)
    if draft is None:
        return
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return
        run.intake_draft = draft.model_dump(exclude={"is_complete"})
        await session.commit()


async def _start_intake_graph_in_background(
    *,
    run_id: str,
    graph: Any,
    initial_state: dict[str, object],
    domain_hint: str | None,
    recursion_limit: int,
    idempotency_key: str,
    background_tasks: set[asyncio.Task[object]],
    accepted_at: datetime,
) -> None:
    """Start intake graph from scratch in background for async create contract."""
    session_factory = get_session_factory()
    config = {"configurable": {"thread_id": run_id}, "recursion_limit": recursion_limit}
    with bind_run(run_id):
        try:
            await _run_graph_with_progress_heartbeat(
                run_id=run_id,
                phase="intake_create",
                graph=graph,
                config=config,
                invoke_coro=graph.ainvoke(initial_state, config=config),
            )
            snapshot = await graph.aget_state(config)
        except asyncio.CancelledError:
            await _handle_graph_cancelled(
                run_id=run_id,
                log_event="api.run.intake.create.cancelled",
            )
            raise
        except Exception as exc:
            async with session_factory() as session:
                record = await session.get(RunCreateRequestRecord, idempotency_key)
                if record is not None:
                    record.status = "failed"
                    record.error_code = type(exc).__name__
                    record.error_message = format_exception_for_log(exc)
                    await session.commit()
            await _mark_run_failed_and_emit(
                run_id=run_id,
                exc=exc,
                log_event="api.run.intake.create.background.failed",
            )
            return

        state_values = snapshot.values if isinstance(snapshot.values, dict) else {}
        await _persist_intake_draft_to_run(run_id=run_id, state_values=state_values)

        async with session_factory() as session:
            record = await session.get(RunCreateRequestRecord, idempotency_key)
            if record is not None:
                record.status = "paused" if snapshot.next else "completed"
                await session.commit()
        if snapshot.next != ():
            next_node = snapshot.next[0] if snapshot.next else None
            log.info(
                "api.run.intake.create.paused",
                next_node=next_node,
                idempotency_key=idempotency_key,
                time_to_first_pause_ms=int(
                    (datetime.now(timezone.utc) - accepted_at).total_seconds() * 1000
                ),
            )
            return

        # Defensive: intake graph might reach terminal unexpectedly; mirror the
        # finalization behavior used by resume paths so run status can't stay running.
        run_status_raw = str(state_values.get("status", "completed"))
        run_status = run_status_raw if run_status_raw in {"completed", "degraded"} else "completed"
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is not None:
                run.status = run_status
                run.finished_at = datetime.now(timezone.utc)
                final_competitors = state_values.get("competitors")
                if isinstance(final_competitors, list) and final_competitors:
                    run.competitors = final_competitors
                await session.commit()
        await _settle_run_billing(run_id=run_id, terminal_status=run_status)
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(run_id=run_id, status=run_status),
        )
        curator_task = asyncio.create_task(
            run_skill_curator_for_run(run_id=run_id, domain_hint=domain_hint),
            name=f"skill_curator_{run_id}",
        )
        background_tasks.add(curator_task)
        curator_task.add_done_callback(background_tasks.discard)


async def _resume_plan_graph_in_background(
    *,
    run_id: str,
    graph: Any,
    resume_payload: dict[str, object],
    domain_hint: str | None,
    recursion_limit: int,
    background_tasks: set[asyncio.Task[object]],
) -> None:
    """Resume the planner-paused graph after the user confirms the plan.

    After planner_wait returns, the graph proceeds to the supervisor and the
    rest of the executor. Terminal handling mirrors `_execute_run_graph`
    (status update, RUN_FINISH event, skill curator follow-up).
    """
    session_factory = get_session_factory()
    config = {"configurable": {"thread_id": run_id}, "recursion_limit": recursion_limit}
    with bind_run(run_id):
        try:
            graph_state = await _run_graph_with_progress_heartbeat(
                run_id=run_id,
                phase="plan_resume",
                graph=graph,
                config=config,
                invoke_coro=graph.ainvoke(Command(resume=resume_payload), config=config),
            )
        except asyncio.CancelledError:
            await _handle_graph_cancelled(
                run_id=run_id, log_event="api.run.plan.resume.cancelled"
            )
            raise
        except Exception as exc:
            await _mark_run_failed_and_emit(
                run_id=run_id, exc=exc, log_event="api.run.plan.resume.failed"
            )
            return

        run_status_raw = str(graph_state.get("status", "completed")) if isinstance(graph_state, dict) else "completed"
        run_status = run_status_raw if run_status_raw in {"completed", "degraded"} else "completed"
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise RuntimeError(f"run_id={run_id} should exist after plan confirm")
            run.status = run_status
            run.finished_at = datetime.now(timezone.utc)
            if isinstance(graph_state, dict):
                final_competitors = graph_state.get("competitors")
                if isinstance(final_competitors, list) and final_competitors:
                    run.competitors = final_competitors
            await session.commit()
            final_status = run.status
        await _settle_run_billing(run_id=run_id, terminal_status=final_status)
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(run_id=run_id, status=final_status),
        )
        curator_task = asyncio.create_task(
            run_skill_curator_for_run(run_id=run_id, domain_hint=domain_hint),
            name=f"skill_curator_{run_id}",
        )
        background_tasks.add(curator_task)
        curator_task.add_done_callback(background_tasks.discard)
        log.info("api.run.plan.resume.finish", status=final_status)


async def _resume_intake_graph_in_background(
    *,
    run_id: str,
    graph: Any,
    resume_payload: dict[str, object],
    domain_hint: str | None,
    recursion_limit: int,
    background_tasks: set[asyncio.Task[object]],
) -> None:
    """Resume the intake-paused graph; either pause again or run to END.

    Either outcome is normal:
      - paused again (more clarification needed): intake_generate already emitted
        INTAKE_CLARIFY_REQUEST inside the graph, so this background path only
        updates the Run row's intake_draft snapshot.
      - reached END: same finalization as `_execute_run_graph` (status, RUN_FINISH,
        skill curator follow-up).
    """
    session_factory = get_session_factory()
    config = {"configurable": {"thread_id": run_id}, "recursion_limit": recursion_limit}
    with bind_run(run_id):
        try:
            await _run_graph_with_progress_heartbeat(
                run_id=run_id,
                phase="intake_resume",
                graph=graph,
                config=config,
                invoke_coro=graph.ainvoke(Command(resume=resume_payload), config=config),
            )
            snapshot = await graph.aget_state(config)
        except asyncio.CancelledError:
            await _handle_graph_cancelled(
                run_id=run_id, log_event="api.run.intake.resume.cancelled"
            )
            raise
        except Exception as exc:
            await _mark_run_failed_and_emit(
                run_id=run_id, exc=exc, log_event="api.run.intake.resume.failed"
            )
            return

        state_values = snapshot.values if isinstance(snapshot.values, dict) else {}
        await _persist_intake_draft_to_run(run_id=run_id, state_values=state_values)

        if snapshot.next != ():
            log.info(
                "api.run.intake.resume.paused",
                next_node=snapshot.next[0] if snapshot.next else None,
            )
            return

        run_status_raw = str(state_values.get("status", "completed"))
        run_status = run_status_raw if run_status_raw in {"completed", "degraded"} else "completed"
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise RuntimeError(f"run_id={run_id} should exist after resume")
            run.status = run_status
            run.finished_at = datetime.now(timezone.utc)
            final_competitors = state_values.get("competitors")
            if isinstance(final_competitors, list) and final_competitors:
                run.competitors = final_competitors
            await session.commit()
            final_status = run.status
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(run_id=run_id, status=final_status),
        )
        curator_task = asyncio.create_task(
            run_skill_curator_for_run(run_id=run_id, domain_hint=domain_hint),
            name=f"skill_curator_{run_id}",
        )
        background_tasks.add(curator_task)
        curator_task.add_done_callback(background_tasks.discard)
        log.info("api.run.intake.resume.finish", status=final_status)


@router.post("/api/runs/intake", response_model=IntakeCreateResponse)
async def create_run_intake(
    payload: IntakeCreateRequest,
    request: Request,
    mode: Literal["chat", "expert"] = Query(default="chat"),
    idempotency_key_header: str | None = Header(default=None, alias="Idempotency-Key"),
) -> IntakeCreateResponse:
    """Async intake creation: accept quickly, run graph in background.

    Maturity goals:
    - request path must not block on LLM latency;
    - retries / double-clicks should replay idempotently;
    - graph failures are surfaced via events + terminal run status.
    """
    if mode == "expert":
        raise APIException(
            status_code=422,
            error_code="EXPERT_MODE_NOT_AVAILABLE",
            message="Expert mode requires the planner node; available from Phase 2.",
        )

    graph = getattr(request.app.state, "compiled_graph", None)
    if graph is None:
        raise APIException(
            status_code=500,
            error_code="GRAPH_NOT_INITIALIZED",
            message="Compiled LangGraph instance is not initialized.",
        )
    background_tasks = getattr(request.app.state, "background_tasks", None)
    if not isinstance(background_tasks, set):
        raise APIException(
            status_code=500,
            error_code="BACKGROUND_TASKS_NOT_INITIALIZED",
            message="Background task registry is not initialized.",
        )

    idempotency_key = _normalize_idempotency_key(
        header_value=idempotency_key_header,
        body_value=payload.client_request_id,
    )
    request_hash = _intake_request_fingerprint(payload)
    session_factory = get_session_factory()
    identity = get_identity()
    inherited_run: Run | None = None
    inherited_draft_raw: dict[str, object] | None = None
    if payload.from_run_id is not None:
        async with session_factory() as session:
            inherited_run = await session.get(Run, payload.from_run_id)
        if inherited_run is None:
            raise APIException(
                status_code=404,
                error_code="FROM_RUN_NOT_FOUND",
                message=f"from_run_id={payload.from_run_id} does not exist",
            )
        if isinstance(inherited_run.intake_draft, dict):
            inherited_draft_raw = inherited_run.intake_draft

    def _pick_non_empty_string(*values: object) -> str | None:
        for value in values:
            normalized = normalize_optional_text(value)
            if normalized is not None:
                return normalized
        return None

    def _pick_text_list(primary: object, inherited: object) -> list[str]:
        if isinstance(primary, list) and primary:
            return stable_unique_text([item for item in primary if isinstance(item, str)])
        if isinstance(inherited, list):
            return stable_unique_text([item for item in inherited if isinstance(item, str)])
        return []

    inherited_reference_urls = (
        list(inherited_run.reference_urls or [])
        if inherited_run is not None
        else []
    )
    normalized_reference_urls = list(payload.reference_urls or []) or inherited_reference_urls
    payload_competitors = _normalize_competitor_inputs(list(payload.competitors_explicit))
    payload_seed_competitors = _normalize_competitor_inputs(list(payload.seed_competitor_ids))
    inherited_seed_competitors = _normalize_competitor_inputs(
        [
            item
            for item in (
                inherited_run.seed_competitor_ids
                if inherited_run is not None and isinstance(inherited_run.seed_competitor_ids, list)
                else []
            )
            if isinstance(item, str)
        ]
    )
    inherited_competitors = _normalize_competitor_inputs(
        list(inherited_run.competitors) if inherited_run is not None else []
    )
    effective_seed_competitors = payload_seed_competitors or inherited_seed_competitors
    effective_competitors = (
        payload_competitors
        or effective_seed_competitors
        or inherited_competitors
    )
    inherited_focus_dimensions = (
        inherited_draft_raw.get("focus_dimensions")
        if isinstance(inherited_draft_raw, dict)
        else None
    )
    inherited_user_role_raw = (
        inherited_draft_raw.get("user_role")
        if isinstance(inherited_draft_raw, dict)
        else None
    )
    inherited_user_role = (
        inherited_user_role_raw
        if inherited_user_role_raw in {"pm", "founder", "sales", "investor"}
        else None
    )
    initial_draft = RunIntakeDraft(
        user_query=payload.user_query,
        user_role=payload.user_role or inherited_user_role,
        domain_hint=_pick_non_empty_string(
            payload.domain_hint,
            inherited_draft_raw.get("domain_hint") if isinstance(inherited_draft_raw, dict) else None,
            inherited_run.domain_hint if inherited_run is not None else None,
        ),
        target_category=_pick_non_empty_string(
            payload.target_category,
            inherited_draft_raw.get("target_category") if isinstance(inherited_draft_raw, dict) else None,
        ),
        category_aliases=_pick_text_list(
            payload.category_aliases,
            inherited_draft_raw.get("category_aliases") if isinstance(inherited_draft_raw, dict) else None,
        ),
        excluded_categories=_pick_text_list(
            payload.excluded_categories,
            inherited_draft_raw.get("excluded_categories") if isinstance(inherited_draft_raw, dict) else None,
        ),
        market_segments=_pick_text_list(
            payload.market_segments,
            inherited_draft_raw.get("market_segments") if isinstance(inherited_draft_raw, dict) else None,
        ),
        competitors_explicit=effective_competitors,
        competitors_discovery_mode=bool(payload.competitors_discovery_mode) and not effective_competitors,
        focus_dimensions=(
            list(payload.focus_dimensions)
            if payload.focus_dimensions
            else [item for item in inherited_focus_dimensions or [] if isinstance(item, str)]
        ),
        report_depth=payload.report_depth,
        reference_urls=normalized_reference_urls,
        self_product=_pick_non_empty_string(
            inherited_draft_raw.get("self_product") if isinstance(inherited_draft_raw, dict) else None,
        ),
        market_scope=_pick_non_empty_string(
            inherited_draft_raw.get("market_scope") if isinstance(inherited_draft_raw, dict) else None,
        ),
        time_context=_pick_non_empty_string(
            inherited_draft_raw.get("time_context") if isinstance(inherited_draft_raw, dict) else None,
        ),
        response_language=resolve_report_language(
            report_language=payload.response_language,
            response_language=(
                inherited_draft_raw.get("response_language")
                if isinstance(inherited_draft_raw, dict)
                else None
            ),
            user_query=payload.user_query,
        ),
        analysis_archetype="comparison" if payload.from_run_id is not None else "comparison",
    )

    async with session_factory() as session:
        existing = await session.get(RunCreateRequestRecord, idempotency_key)
        if existing is not None:
            if existing.request_hash != request_hash:
                raise APIException(
                    status_code=409,
                    error_code="INTAKE_CREATE_IDEMPOTENCY_CONFLICT",
                    message=(
                        "Idempotency-Key 已绑定到不同请求体。"
                        "请更换 key 或重试原请求。"
                    ),
                )
            run = await session.get(Run, existing.run_id)
            if run is None:
                raise APIException(
                    status_code=409,
                    error_code="INTAKE_CREATE_REPLAY_MISSING_RUN",
                    message="幂等记录存在，但关联 run 不存在，请更换 key 重试。",
                )
            replay_draft = (
                RunIntakeDraft.model_validate(run.intake_draft)
                if isinstance(run.intake_draft, dict)
                else initial_draft
            )
            log.info(
                "api.run.intake.create.replay",
                run_id=run.run_id,
                idempotency_key=idempotency_key,
                replay_status=existing.status,
            )
            return IntakeCreateResponse(
                run_id=run.run_id,
                status=run.status,
                phase=_derive_run_phase(run) or "intake",
                intake_draft=replay_draft,
                first_clarify_request=None,
            )

    run_id = make_id("run_")
    reservation_amount = default_reservation_amount(None)
    try:
        reservation = await quota_client.reserve(
            user_id=identity.user_id,
            amount_micro_points=reservation_amount,
            run_id=run_id,
            trace_id=run_id,
        )
    except Exception as exc:
        raise APIException(
            status_code=402,
            error_code="QUOTA_RESERVATION_FAILED",
            message="积分不足或积分服务暂不可用，请稍后重试。",
        ) from exc
    accepted_at = datetime.now(timezone.utc)
    with bind_run(run_id):
        log.info(
            "api.run.intake.create.start",
            idempotency_key=idempotency_key,
            user_role=payload.user_role,
            competitor_explicit_count=len(payload.competitors_explicit),
            competitor_discovery_mode=payload.competitors_discovery_mode,
        )

        async with session_factory() as session:
            session.add(
                Run(
                    run_id=run_id,
                    owner_user_id=identity.user_id,
                    user_query=payload.user_query,
                    domain_hint=initial_draft.domain_hint,
                    reference_urls=normalized_reference_urls,
                    status="running",
                    target_roles=[],
                    competitors=list(initial_draft.competitors_explicit),
                    intake_draft=initial_draft.model_dump(exclude={"is_complete"}),
                    parent_run_id=payload.from_run_id,
                    seed_competitor_ids=effective_seed_competitors or None,
                    reservation_id=reservation.reservation_id,
                    reserved_micro_points=reservation.amount_micro_points,
                    billing_status="RESERVED",
                )
            )
            session.add(
                RunCreateRequestRecord(
                    idempotency_key=idempotency_key,
                    run_id=run_id,
                    request_hash=request_hash,
                    status="accepted",
                )
            )
            try:
                await session.commit()
            except IntegrityError:
                # Concurrent request with same idempotency key won the race.
                await session.rollback()
                existing = await session.get(RunCreateRequestRecord, idempotency_key)
                if existing is None:
                    raise
                if existing.request_hash != request_hash:
                    raise APIException(
                        status_code=409,
                        error_code="INTAKE_CREATE_IDEMPOTENCY_CONFLICT",
                        message=(
                            "Idempotency-Key 已绑定到不同请求体。"
                            "请更换 key 或重试原请求。"
                        ),
                    )
                existing_run = await session.get(Run, existing.run_id)
                if existing_run is None:
                    raise APIException(
                        status_code=409,
                        error_code="INTAKE_CREATE_REPLAY_MISSING_RUN",
                        message="幂等记录存在，但关联 run 不存在，请更换 key 重试。",
                    )
                if identity.user_id != 0 and int(existing_run.owner_user_id or 0) != identity.user_id:
                    # Idempotency keys are scoped to the authenticated owner. Do
                    # not disclose another user's run or draft on a key collision.
                    raise APIException(
                        status_code=404,
                        error_code="INTAKE_CREATE_NOT_FOUND",
                        message="请求不存在。",
                    )
                replay_draft = (
                    RunIntakeDraft.model_validate(existing_run.intake_draft)
                    if isinstance(existing_run.intake_draft, dict)
                    else initial_draft
                )
                return IntakeCreateResponse(
                    run_id=existing_run.run_id,
                    status=existing_run.status,
                    phase=_derive_run_phase(existing_run) or "intake",
                    intake_draft=replay_draft,
                    first_clarify_request=None,
                )

        initial_state: dict[str, object] = {
            "run_id": run_id,
            "owner_user_id": identity.user_id,
            "user_query": payload.user_query,
            "domain_hint": initial_draft.domain_hint,
            "market_scope": initial_draft.market_scope,
            "response_language": initial_draft.response_language,
            "reference_urls": normalized_reference_urls,
            "competitors": list(initial_draft.competitors_explicit),
            "discovered_competitors": [],
            "discovered_competitor_sources": {},
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "replan_count": 0,
            "current_iteration": 0,
            "pending_tool_args": {},
            "qa_outcome": None,
            "qa_reject_to": None,
            "qa_rejection_count": 0,
            "pending_review_target_step_id": None,
            "qa_reasons": [],
            "status": "running",
            "phase": "intake",
            "report_depth_selection_pending": False,
            "intake_draft": initial_draft,
            "intake_history": [],
            "pending_clarify": None,
        }
        intake_config = _graph_invoke_config(
            run_id=run_id,
            report_depth=payload.report_depth,
        )
        task = asyncio.create_task(
            _start_intake_graph_in_background(
                run_id=run_id,
                graph=graph,
                initial_state=initial_state,
                domain_hint=payload.domain_hint,
                recursion_limit=int(intake_config["recursion_limit"]),
                idempotency_key=idempotency_key,
                background_tasks=background_tasks,
                accepted_at=accepted_at,
            ),
            name=f"intake_create_{run_id}",
        )
        _register_background_task(request, task)

        log.info(
            "api.run.intake.create.accepted",
            phase="intake",
            idempotency_key=idempotency_key,
            intake_create_accept_latency_ms=int(
                (datetime.now(timezone.utc) - accepted_at).total_seconds() * 1000
            ),
        )

    return IntakeCreateResponse(
        run_id=run_id,
        status="running",
        phase="intake",
        intake_draft=initial_draft,
        first_clarify_request=None,
    )


@router.post("/api/runs/{run_id}/intake/reply", response_model=RunAcceptedResponse)
async def reply_run_intake(
    run_id: str,
    payload: IntakeUserReply,
    request: Request,
) -> RunAcceptedResponse:
    """Invariant C: return accepted immediately; resume the graph off the request path.

    The graph re-enters intake_generate after the wait node returns. Whether it
    asks another clarify or completes is observed by the FE via SSE; the response
    body intentionally carries no clarify payload to keep this endpoint cheap.
    """
    graph = getattr(request.app.state, "compiled_graph", None)
    if graph is None:
        raise APIException(
            status_code=500,
            error_code="GRAPH_NOT_INITIALIZED",
            message="Compiled LangGraph instance is not initialized.",
        )
    background_tasks = getattr(request.app.state, "background_tasks", None)
    if not isinstance(background_tasks, set):
        raise APIException(
            status_code=500,
            error_code="BACKGROUND_TASKS_NOT_INITIALIZED",
            message="Background task registry is not initialized.",
        )

    session_factory = get_session_factory()
    with bind_run(run_id):
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} does not exist",
                )
            if run.status != "running":
                raise APIException(
                    status_code=409,
                    error_code="RUN_NOT_RESUMABLE",
                    message=f"run status={run.status} is not resumable",
                )
            domain_hint = run.domain_hint
            report_depth = _resolve_run_report_depth(run)

        # Verify the graph is paused at a reply-compatible node. We reuse the
        # same endpoint for both intake clarify turns and the post-intake
        # planning-profile selection gate.
        snapshot = await graph.aget_state({"configurable": {"thread_id": run_id}})
        if snapshot.next not in {("intake_wait",), ("planning_profile_wait",)}:
            raise APIException(
                status_code=409,
                error_code="INTAKE_NOT_AWAITING_REPLY",
                message=(
                    "run is not paused at an intake/profile reply step; "
                    f"current next_node={list(snapshot.next)}"
                ),
            )

        resume_payload = payload.model_dump()
        intake_resume_config = _graph_invoke_config(
            run_id=run_id,
            report_depth=report_depth,
        )
        task = asyncio.create_task(
            _resume_intake_graph_in_background(
                run_id=run_id,
                graph=graph,
                resume_payload=resume_payload,
                domain_hint=domain_hint,
                recursion_limit=int(intake_resume_config["recursion_limit"]),
                background_tasks=background_tasks,
            ),
            name=f"intake_resume_{run_id}",
        )
        _register_background_task(request, task)
        log.info(
            "api.run.intake.reply.accepted",
            reply_text_len=len(payload.text),
            reply_option_count=len(payload.selected_options),
        )

    return RunAcceptedResponse(run_id=run_id, status="running")


@router.post("/api/runs/{run_id}/plan/confirm", response_model=RunAcceptedResponse)
async def confirm_run_plan(
    run_id: str,
    payload: PlanConfirmRequest,
    request: Request,
) -> RunAcceptedResponse:
    """Phase 2 (Invariant C): resume the graph past planner_wait.

    The graph proceeds to the supervisor and the rest of the executor in a
    background task. Phase β honors `disabled_task_ids` (must reference tasks
    in the pending plan) and `additional_tasks` (server forces
    source="user", priority="user_pinned"; the planner_wait node validates
    them and merges into plan_tree).
    """
    graph = getattr(request.app.state, "compiled_graph", None)
    if graph is None:
        raise APIException(
            status_code=500,
            error_code="GRAPH_NOT_INITIALIZED",
            message="Compiled LangGraph instance is not initialized.",
        )
    background_tasks = getattr(request.app.state, "background_tasks", None)
    if not isinstance(background_tasks, set):
        raise APIException(
            status_code=500,
            error_code="BACKGROUND_TASKS_NOT_INITIALIZED",
            message="Background task registry is not initialized.",
        )

    session_factory = get_session_factory()
    with bind_run(run_id):
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} does not exist",
                )
            if run.status != "running":
                raise APIException(
                    status_code=409,
                    error_code="RUN_NOT_RESUMABLE",
                    message=f"run status={run.status} is not resumable",
                )
            domain_hint = run.domain_hint
            report_depth = _resolve_run_report_depth(run)

        # Verify the graph is actually paused at planner_wait. Resuming from a
        # non-plan pause would inject the PlanConfirmRequest into the wrong
        # interrupt payload (mirrors the intake/reply guard above).
        snapshot = await graph.aget_state({"configurable": {"thread_id": run_id}})
        if snapshot.next != ("planner_wait",):
            raise APIException(
                status_code=409,
                error_code="PLAN_NOT_AWAITING_CONFIRM",
                message=(
                    "run is not paused at the plan-confirm step; "
                    f"current next_node={list(snapshot.next)}"
                ),
            )

        resume_payload = payload.model_dump()
        plan_resume_config = _graph_invoke_config(
            run_id=run_id,
            report_depth=report_depth,
        )
        task = asyncio.create_task(
            _resume_plan_graph_in_background(
                run_id=run_id,
                graph=graph,
                resume_payload=resume_payload,
                domain_hint=domain_hint,
                recursion_limit=int(plan_resume_config["recursion_limit"]),
                background_tasks=background_tasks,
            ),
            name=f"plan_resume_{run_id}",
        )
        _register_background_task(request, task)
        log.info(
            "api.run.plan.confirm.accepted",
            disabled_task_count=len(payload.disabled_task_ids),
            additional_task_count=len(payload.additional_tasks),
        )

    return RunAcceptedResponse(run_id=run_id, status="running")


@router.post(
    "/api/runs/{run_id}/follow-up",
    response_model=FollowUpAcceptedResponse,
)
async def submit_run_follow_up(
    run_id: str,
    payload: FollowUpRequest,
    request: Request,
) -> FollowUpAcceptedResponse:
    """Phase 4: append a mid-run user addendum to the supervisor's inbox.

    Persisted on `runs.follow_ups` (JSONB list); the supervisor reads pending
    entries at the start of each iteration, injects them into its prompt,
    then marks them consumed after the LLM decision. We deliberately do NOT
    touch the LangGraph state directly: the graph is mid-execution (not at
    an interrupt), so `aupdate_state` on a running thread is unsafe — the
    DB inbox is the lock-free channel.
    """
    graph = getattr(request.app.state, "compiled_graph", None)
    if graph is None:
        raise APIException(
            status_code=500,
            error_code="GRAPH_NOT_INITIALIZED",
            message="Compiled LangGraph instance is not initialized.",
        )

    session_factory = get_session_factory()
    with bind_run(run_id):
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} does not exist",
                )
            if run.status != "running":
                raise APIException(
                    status_code=409,
                    error_code="FOLLOWUP_RUN_NOT_RUNNING",
                    message=(
                        f"run status={run.status} cannot accept follow-up "
                        "(must be running and past plan confirmation)"
                    ),
                )
            plan_tree_value = run.plan_tree
            plan_confirmed = (
                isinstance(plan_tree_value, dict)
                and plan_tree_value.get("confirmed_at") is not None
            )
            if not plan_confirmed:
                raise APIException(
                    status_code=409,
                    error_code="FOLLOWUP_NOT_EXECUTING",
                    message=(
                        "follow-up is only accepted after plan confirmation — "
                        "use POST /intake/reply or /plan/confirm instead"
                    ),
                )

        snapshot = await graph.aget_state({"configurable": {"thread_id": run_id}})
        if snapshot.next in {("intake_wait",), ("planner_wait",)}:
            raise APIException(
                status_code=409,
                error_code="FOLLOWUP_GRAPH_PAUSED",
                message=(
                    "graph is paused awaiting a structured reply; "
                    f"use the matching endpoint instead (next_node={list(snapshot.next)})"
                ),
            )

        received_at = datetime.now(timezone.utc).isoformat()
        entry = FollowUpEntry(
            text=payload.text,
            applies_to_stage=payload.applies_to_stage,
            received_at=received_at,
        )
        entry_dict = entry.model_dump(mode="json")

        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                # Defensive: another tab could have hit /reset between our two
                # reads. Surface 404 rather than persisting an orphan entry.
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} no longer exists",
                )
            existing = list(run.follow_ups or [])
            existing.append(entry_dict)
            run.follow_ups = existing
            await session.commit()

        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.FOLLOWUP_RECEIVED,
            payload={
                "follow_up_id": entry.id,
                "text": entry.text,
                "applies_to_stage": entry.applies_to_stage,
                "received_at": received_at,
            },
        )
        log.info(
            "api.run.follow_up.accepted",
            follow_up_id=entry.id,
            applies_to_stage=entry.applies_to_stage,
            text_len=len(entry.text),
        )

    return FollowUpAcceptedResponse(
        run_id=run_id,
        follow_up_id=entry.id,
        received_at=received_at,
    )


@router.post("/api/runs/{run_id}/resume", response_model=RunCreateResponse)
async def resume_run(run_id: str, request: Request) -> RunCreateResponse:
    session_factory = get_session_factory()
    with bind_run(run_id):
        log.info("api.run.resume.start")
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} does not exist",
                )
            if run.status != "running":
                raise APIException(
                    status_code=409,
                    error_code="RUN_NOT_RESUMABLE",
                    message=f"run_id={run_id} status={run.status} cannot resume",
                )
            report_depth = _resolve_run_report_depth(run)

        graph = getattr(request.app.state, "compiled_graph", None)
        if graph is None:
            raise APIException(
                status_code=500,
                error_code="GRAPH_NOT_INITIALIZED",
                message="Compiled LangGraph instance is not initialized.",
            )
        config = _graph_invoke_config(run_id=run_id, report_depth=report_depth)
        graph_state = await graph.ainvoke(None, config=config)

        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=500,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} should exist before resume update",
                )
            run_domain_hint = run.domain_hint
            run_status = str(graph_state.get("status", "completed"))
            run.status = run_status if run_status in {"completed", "degraded"} else "completed"
            run.finished_at = datetime.now(timezone.utc)
            await session.commit()
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(run_id=run_id, status=run.status),
        )
        task = asyncio.create_task(
            run_skill_curator_for_run(run_id=run_id, domain_hint=run_domain_hint),
            name=f"skill_curator_{run_id}",
        )
        _register_background_task(request, task)
        log.info("api.run.resume.finish", status=run.status)

    return RunCreateResponse(
        run_id=run_id,
        status=run.status,
        message="Run resumed from checkpoint.",
    )


@router.post("/api/runs/{run_id}/reset", response_model=RunCreateResponse)
async def reset_run(run_id: str, payload: RunResetRequest, request: Request) -> RunCreateResponse:
    session_factory = get_session_factory()
    with bind_run(run_id):
        log.info("api.run.reset.start", reset_to=payload.reset_to)
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} does not exist",
                )
            if run.status not in RESETTABLE_RUN_STATUS:
                raise APIException(
                    status_code=409,
                    error_code="RUN_NOT_RESETTABLE",
                    message=f"run_id={run_id} status={run.status} cannot reset",
                )
            run_domain_hint = run.domain_hint
            report_depth = _resolve_run_report_depth(run)

        graph = getattr(request.app.state, "compiled_graph", None)
        if graph is None:
            raise APIException(
                status_code=500,
                error_code="GRAPH_NOT_INITIALIZED",
                message="Compiled LangGraph instance is not initialized.",
            )
        config = _graph_invoke_config(run_id=run_id, report_depth=report_depth)
        state_snapshot = await graph.aget_state(config)
        if not _has_checkpoint_state(state_snapshot.values):
            raise APIException(
                status_code=409,
                error_code="RUN_CHECKPOINT_NOT_FOUND",
                message=f"run_id={run_id} has no checkpoint state to reset from",
            )

        await _cleanup_trace_for_reset(run_id=run_id, reset_to=payload.reset_to)

        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=500,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} should exist before reset replay",
                )
            run.status = "running"
            run.finished_at = None
            await session.commit()

        reset_values = _build_reset_state_values(reset_to=payload.reset_to)
        await graph.aupdate_state(config, reset_values, as_node="supervisor")
        graph_state = await graph.ainvoke(None, config=config)

        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=500,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} should exist before reset status update",
                )
            run.status = _coerce_run_status(graph_state)
            run.finished_at = datetime.now(timezone.utc)
            await session.commit()

        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(run_id=run_id, status=run.status),
        )
        task = asyncio.create_task(
            run_skill_curator_for_run(run_id=run_id, domain_hint=run_domain_hint),
            name=f"skill_curator_{run_id}",
        )
        _register_background_task(request, task)
        log.info("api.run.reset.finish", reset_to=payload.reset_to, status=run.status)

    return RunCreateResponse(
        run_id=run_id,
        status=run.status,
        message=f"Run reset to {payload.reset_to} and replayed from checkpoint.",
    )


@router.get("/api/runs/{run_id}/events")
async def stream_run_events(run_id: str, request: Request) -> StreamingResponse:
    event_bus = _event_bus_from_request(request)
    if event_bus is None:
        raise APIException(
            status_code=503,
            error_code="EVENT_BUS_NOT_INITIALIZED",
            message="Run event stream is not initialized.",
        )

    return StreamingResponse(
        _run_event_stream(event_bus=event_bus, run_id=run_id),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
    )


@router.get("/api/runs/{run_id}", response_model=RunDetailResponse)
async def get_run(run_id: str) -> RunDetailResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        return _to_run_detail(run)


class IntakeSessionResponse(BaseModel):
    """Server-side projection of an intake chat session (Invariant: server is the
    source of truth). Lets the FE rebuild the chat after a refresh/reconnect from
    `history` + `pending_clarify` instead of relying on live-only SSE.
    """

    run_id: str
    status: str
    phase: str | None
    awaiting_user: bool
    intake_draft: RunIntakeDraft | None
    pending_clarify: IntakeClarifyRequest | None
    history: list[IntakeExchange]


@router.get("/api/runs/{run_id}/intake-session", response_model=IntakeSessionResponse)
async def get_run_intake_session(run_id: str, request: Request) -> IntakeSessionResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        phase = _derive_run_phase(run)
        db_draft = (
            RunIntakeDraft.model_validate(run.intake_draft)
            if run.intake_draft is not None
            else None
        )
        run_status = run.status

    graph = getattr(request.app.state, "compiled_graph", None)
    if graph is None:
        raise APIException(
            status_code=503,
            error_code="GRAPH_NOT_INITIALIZED",
            message="agent graph is not ready",
        )
    snapshot = await graph.aget_state({"configurable": {"thread_id": run_id}})
    values: dict[str, object] = snapshot.values or {}

    draft = _coerce_intake_draft_from_state(values) or db_draft

    raw_pending = values.get("pending_clarify")
    if isinstance(raw_pending, IntakeClarifyRequest):
        pending = raw_pending
    elif isinstance(raw_pending, dict):
        pending = IntakeClarifyRequest.model_validate(raw_pending)
    else:
        pending = None

    raw_history = values.get("intake_history") or []
    history = [
        item if isinstance(item, IntakeExchange) else IntakeExchange.model_validate(item)
        for item in raw_history
        if isinstance(item, (IntakeExchange, dict))
    ]

    return IntakeSessionResponse(
        run_id=run_id,
        status=run_status,
        phase=phase,
        awaiting_user=snapshot.next in {("intake_wait",), ("planning_profile_wait",)},
        intake_draft=draft,
        pending_clarify=pending,
        history=history,
    )


class RunPatchRequest(BaseModel):
    user_query: str | None = None
    # Manual rename for the short title. Use this instead of mutating user_query
    # when the user just wants a cleaner card label — user_query is the
    # original prompt and should stay immutable in most cases.
    title: str | None = Field(default=None, max_length=120)
    status: Literal["cancelled"] | None = None
    cancel_reason: str | None = Field(default=None, max_length=200)

    @field_validator("title")
    @classmethod
    def _normalize_title(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None

    @field_validator("cancel_reason")
    @classmethod
    def _normalize_cancel_reason(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None


class RunDeleteResponse(BaseModel):
    run_id: str
    deleted: bool


class BatchDeleteRequest(BaseModel):
    run_ids: list[str] = Field(..., min_length=1, max_length=50)


class BatchDeleteResponse(BaseModel):
    deleted_count: int
    not_found: list[str]


ClearRunsStatus = Literal["all", "completed", "degraded", "failed", "cancelled", "running"]


class ClearRunsRequest(BaseModel):
    status: ClearRunsStatus = "all"
    keyword: str | None = Field(default=None, max_length=200)
    include_running: bool = False

    @field_validator("keyword")
    @classmethod
    def _normalize_keyword(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized if normalized else None


class ClearRunsResponse(BaseModel):
    deleted_count: int
    deleted_run_ids: list[str]
    skipped_running_count: int
    pruned_skill_candidate_refs: int


async def _prune_supporting_run_refs(
    *,
    session: Any,
    deleted_run_ids: set[str],
) -> int:
    if not deleted_run_ids:
        return 0
    candidates = (await session.execute(select(SkillCandidateRecord))).scalars().all()
    pruned_refs = 0
    for candidate in candidates:
        kept_ids: list[str] = []
        for run_id in candidate.supporting_run_ids:
            if run_id in deleted_run_ids:
                pruned_refs += 1
                continue
            kept_ids.append(run_id)
        if len(kept_ids) != len(candidate.supporting_run_ids):
            candidate.supporting_run_ids = kept_ids
    return pruned_refs


@router.delete("/api/runs/{run_id}", response_model=RunDeleteResponse)
async def delete_run(run_id: str) -> RunDeleteResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        await session.delete(run)
        await session.commit()
    return RunDeleteResponse(run_id=run_id, deleted=True)


@router.patch("/api/runs/{run_id}", response_model=RunDetailResponse)
async def patch_run(
    run_id: str, payload: RunPatchRequest, request: Request
) -> RunDetailResponse:
    """Rename + cooperative cancel.

    Cancel path (status="cancelled" while run is running):
      1. Flip DB to "cancelled" (user intent wins over the background's eventual
         "failed" if its CancelledError races back).
      2. Cancel any in-flight graph tasks named with this run_id.
      3. Emit RUN_FINISH so SSE consumers (LiveRunPage) stop polling immediately.
    """
    should_cancel_tasks = False
    cancel_reason = payload.cancel_reason
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        if payload.user_query is not None:
            run.user_query = payload.user_query
        if payload.title is not None:
            run.title = payload.title
        if payload.status == "cancelled" and run.status == "running":
            run.status = "cancelled"
            run.finished_at = datetime.now(timezone.utc)
            should_cancel_tasks = True
        await session.commit()
        await session.refresh(run)

    if should_cancel_tasks:
        # The graph task can be cancelled before it reaches its normal finalizer.
        # Settle the usage snapshot before publishing the terminal event.
        await _settle_run_billing(run_id=run_id, terminal_status="cancelled")
        background_tasks = getattr(request.app.state, "background_tasks", None)
        cancelled_count = _cancel_background_tasks_for_run(
            background_tasks=background_tasks if isinstance(background_tasks, set) else None,
            run_id=run_id,
        )
        with bind_run(run_id):
            log.info(
                "api.run.cancel",
                cancelled_task_count=cancelled_count,
                cancel_reason=cancel_reason,
            )
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload=_build_run_finish_payload(
                run_id=run_id,
                status="cancelled",
                error_type="UserCancelled",
                error_message=cancel_reason or "用户已停止此次分析",
            ),
        )
    return _to_run_detail(run)


@router.post("/api/runs/batch-delete", response_model=BatchDeleteResponse)
async def batch_delete_runs(payload: BatchDeleteRequest) -> BatchDeleteResponse:
    session_factory = get_session_factory()
    not_found: list[str] = []
    deleted_count = 0
    async with session_factory() as session:
        for rid in payload.run_ids:
            run = await session.get(Run, rid)
            if run is None:
                not_found.append(rid)
            else:
                await session.delete(run)
                deleted_count += 1
        await session.commit()
    return BatchDeleteResponse(deleted_count=deleted_count, not_found=not_found)


@router.post("/api/runs/clear", response_model=ClearRunsResponse)
async def clear_runs(payload: ClearRunsRequest) -> ClearRunsResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        query = select(Run.run_id, Run.status)
        if payload.status != "all":
            query = query.where(Run.status == payload.status)
        if payload.keyword is not None:
            query = query.where(Run.user_query.ilike(f"%{payload.keyword}%"))
        rows = (await session.execute(query)).all()

        run_ids_to_delete: list[str] = []
        skipped_running_count = 0
        for run_id, status in rows:
            if status == "running" and not payload.include_running:
                skipped_running_count += 1
                continue
            run_ids_to_delete.append(run_id)
        deleted_run_ids_set = set(run_ids_to_delete)
        pruned_skill_candidate_refs = await _prune_supporting_run_refs(
            session=session,
            deleted_run_ids=deleted_run_ids_set,
        )
        if run_ids_to_delete:
            await session.execute(delete(Run).where(Run.run_id.in_(run_ids_to_delete)))
        await session.commit()
    return ClearRunsResponse(
        deleted_count=len(run_ids_to_delete),
        deleted_run_ids=run_ids_to_delete,
        skipped_running_count=skipped_running_count,
        pruned_skill_candidate_refs=pruned_skill_candidate_refs,
    )


@router.get("/api/runs/{run_id}/report", response_model=RunReportResponse)
async def get_run_report(run_id: str) -> RunReportResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        report = (
            await session.execute(
                select(Report).where(Report.run_id == run_id).order_by(Report.created_at.desc()).limit(1)
            )
        ).scalars().first()
        if report is None:
            raise APIException(
                status_code=404,
                error_code="REPORT_NOT_FOUND",
                message=f"report for run_id={run_id} does not exist",
            )
        evidence_rows = (
            await session.execute(
                select(EvidenceRecord)
                .where(EvidenceRecord.run_id == run_id)
                .order_by(EvidenceRecord.created_at.asc())
            )
        ).scalars().all()

    evidence_id_to_brief: dict[str, EvidenceBriefResponse] = {}
    for evidence in evidence_rows:
        evidence_id_to_brief[evidence.id] = EvidenceBriefResponse(
            evidence_id=evidence.id,
            source_type=evidence.source_type,
            source_url=evidence.source_url,
            source_title=evidence.source_title,
            competitor_id=_extract_competitor_id(evidence.span),
        )

    return RunReportResponse(
        run_id=run.run_id,
        status=run.status,
        content_markdown=report.content_markdown,
        content_json=report.content_json,
        generated_at=report.created_at.isoformat(),
        evidence_id_to_brief=evidence_id_to_brief,
    )


@router.get("/api/runs/{run_id}/evidence", response_model=list[EvidenceListItemResponse])
async def get_run_evidence(
    run_id: str,
    competitor_id: str | None = Query(default=None),
    source_type: str | None = Query(default=None),
    evidence_id: str | None = Query(default=None),
) -> list[EvidenceListItemResponse]:
    normalized_competitor_id = competitor_id.strip() if isinstance(competitor_id, str) else None
    normalized_source_type = source_type.strip() if isinstance(source_type, str) else None
    requested_evidence_ids = {
        item.strip()
        for item in evidence_id.split(",")
        if item.strip()
    } if isinstance(evidence_id, str) else set()
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        query = select(EvidenceRecord).where(EvidenceRecord.run_id == run_id)
        if normalized_source_type:
            query = query.where(EvidenceRecord.source_type == normalized_source_type)
        if requested_evidence_ids:
            query = query.where(EvidenceRecord.id.in_(requested_evidence_ids))
        evidence_rows = (
            await session.execute(query.order_by(EvidenceRecord.created_at.asc()))
        ).scalars().all()

    if normalized_competitor_id:
        evidence_rows = [
            item
            for item in evidence_rows
            if _extract_competitor_id(item.span) == normalized_competitor_id
        ]

    return [
        EvidenceListItemResponse(
            evidence_id=evidence.id,
            run_id=evidence.run_id,
            source_type=evidence.source_type,
            source_url=evidence.source_url,
            source_title=evidence.source_title,
            quote=evidence.quote,
            sanitized_text=evidence.sanitized_text,
            source_language=_extract_source_language(evidence.span),
            translated_excerpt=_extract_translated_excerpt(evidence.span),
            competitor_id=_extract_competitor_id(evidence.span),
            metadata=evidence.span,
            desensitized=evidence.desensitized,
            collected_at=evidence.collected_at.isoformat(),
            created_at=evidence.created_at.isoformat(),
        )
        for evidence in evidence_rows
    ]


@router.get("/api/runs/{run_id}/conclusions", response_model=RunConclusionsResponse)
async def get_run_conclusions(run_id: str) -> RunConclusionsResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        items_raw = await load_conclusions_for_run(session=session, run_id=run_id)

    return RunConclusionsResponse(
        run_id=run_id,
        items=[ConclusionItemResponse.model_validate(item) for item in items_raw],
    )


_COMPETITOR_PROFILE_FIELDS: tuple[str, ...] = ("role", "segment", "vendor", "introduction")

_DEFAULT_COMPETITOR_SEGMENT = "AI 编程工具 / AI IDE / 代码智能体"

# A research run can legitimately finish with a product in the requested
# competitor list but without a discovery source (for example, an official
# page was rate-limited). Keep the product visible in the knowledge matrix and
# provide conservative, editable metadata instead of rendering a row of dashes.
_CANONICAL_COMPETITOR_PROFILES: tuple[tuple[tuple[str, ...], dict[str, str]], ...] = (
    (
        ("cursor",),
        {
            "vendor": "Anysphere",
            "introduction": "Cursor 是以 AI Agent、代码库上下文和 Debug 工作流为核心的 AI IDE。",
        },
    ),
    (
        ("github copilot", "copilot"),
        {
            "vendor": "GitHub / Microsoft",
            "introduction": "GitHub Copilot 是集成在编辑器、GitHub 与企业研发流程中的 AI 编程助手。",
        },
    ),
    (
        ("windsurf",),
        {
            "vendor": "Codeium / Windsurf",
            "introduction": "Windsurf 是面向代码库级上下文和 Agent 式开发流程的 AI IDE。",
        },
    ),
    (
        ("文心 comate", "文心快码 comate"),
        {
            "vendor": "百度",
            "introduction": "文心 Comate（文心快码）是百度推出的企业级 AI 编程助手。",
        },
    ),
    (
        ("codebuddy",),
        {
            "vendor": "腾讯",
            "introduction": "CodeBuddy 是腾讯推出的 AI 编程工具，覆盖 IDE、插件与企业研发场景。",
        },
    ),
    (
        ("通义灵码",),
        {
            "vendor": "阿里云",
            "introduction": "通义灵码是阿里云推出的 AI 编程助手，覆盖补全、问答和智能体能力。",
        },
    ),
    (
        ("fitten code",),
        {
            "vendor": "非十科技",
            "introduction": "Fitten Code 是面向开发者的 AI 编程助手，支持 IDE 插件和代码生成。",
        },
    ),
    (
        ("devin",),
        {
            "vendor": "Cognition",
            "introduction": "Devin 定位为可规划任务、调用工具并执行复杂编程工作的 AI 软件工程师。",
        },
    ),
    (
        ("qoder",),
        {
            "vendor": "阿里云",
            "introduction": "Qoder 是面向代码库理解、智能对话和复杂任务执行的 AI 编程工具。",
        },
    ),
    (
        ("codegeex",),
        {
            "vendor": "智谱 AI",
            "introduction": "CodeGeeX 是支持多种 IDE 的 AI 编程助手，覆盖补全、生成和问答。",
        },
    ),
    (
        ("claude code",),
        {
            "vendor": "Anthropic",
            "introduction": "Claude Code 是 Anthropic 面向终端和代码库任务的 AI 代码智能体。",
        },
    ),
    (
        ("augment code",),
        {
            "vendor": "Augment",
            "introduction": "Augment Code 聚焦代码库理解、Agent 执行和软件开发全流程协作。",
        },
    ),
    (
        ("华为 codearts doer 编程助手",),
        {
            "vendor": "华为",
            "introduction": "华为 CodeArts Doer 编程助手面向企业研发流程提供代码生成和开发辅助。",
        },
    ),
)


def _clean_profile_value(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip()
    return cleaned or None


def _canonical_competitor_profile(competitor_id: str) -> dict[str, str | None]:
    normalized_keys = _alias_keys_for_competitor(competitor_id)
    normalized_keys.add(re.sub(r"[\W_]+", "", competitor_id.casefold(), flags=re.UNICODE))
    for aliases, values in _CANONICAL_COMPETITOR_PROFILES:
        alias_keys = {
            key
            for alias in aliases
            for key in (
                _normalize_competitor_key(alias),
                re.sub(r"[\W_]+", "", alias.casefold(), flags=re.UNICODE),
            )
            if key
        }
        if normalized_keys.intersection(alias_keys):
            return {
                "role": "direct_competitor",
                "segment": _DEFAULT_COMPETITOR_SEGMENT,
                "vendor": values.get("vendor"),
                "introduction": values.get("introduction"),
            }
    return {field: None for field in _COMPETITOR_PROFILE_FIELDS}


def _build_competitor_profiles(
    *,
    plan_tree: object,
    discovery_sources: dict[str, object],
    competitor_ids: list[str] | None = None,
) -> list[KnowledgeCompetitorResponse]:
    """Resolve the canonical competitor profile for a run.

    Competitor profiles originate in discovery and are mirrored into
    `plan_tree.competitor_sources` only when the planning path persists it; that
    mirror is missing for some runs. We treat the plan_tree mirror as primary and
    fall back to the discovery step payload so the frontend has a single source.
    """
    plan_sources: dict[str, object] = {}
    if isinstance(plan_tree, dict):
        raw = plan_tree.get("competitor_sources")
        if isinstance(raw, dict):
            plan_sources = raw

    all_competitor_ids = list(
        dict.fromkeys(
            [
                *(competitor_ids or []),
                *discovery_sources.keys(),
                *plan_sources.keys(),
            ]
        )
    )
    profiles: list[KnowledgeCompetitorResponse] = []
    for competitor_id in all_competitor_ids:
        if not isinstance(competitor_id, str) or not competitor_id.strip():
            continue
        canonical = _canonical_competitor_profile(competitor_id)
        merged: dict[str, str | None] = dict(canonical)
        for source in (discovery_sources.get(competitor_id), plan_sources.get(competitor_id)):
            if not isinstance(source, dict):
                continue
            role = _clean_profile_value(source.get("candidate_role"))
            if role is not None:
                merged["role"] = role
            for field in ("segment", "vendor", "introduction"):
                value = _clean_profile_value(source.get(field))
                if value is not None:
                    merged[field] = value
        # Keep the matrix's basic taxonomy stable even when a discovery model
        # returns the product name as its vendor (e.g. "Devin" or "CodeGeeX").
        # Research-provided introductions remain preferred because they are
        # run-specific and may contain a more precise positioning statement.
        if canonical.get("segment") is not None:
            merged["segment"] = canonical["segment"]
        if canonical.get("vendor") is not None:
            merged["vendor"] = canonical["vendor"]
        profiles.append(
            KnowledgeCompetitorResponse(competitor_id=competitor_id, **merged)
        )
    return profiles


async def _latest_discovery_sources(
    *, session: AsyncSession, run_id: str
) -> dict[str, object]:
    step_rows = (
        await session.execute(
            select(Step)
            .where(Step.run_id == run_id, Step.agent_name == "discovery")
            .order_by(Step.created_at.desc())
        )
    ).scalars().all()
    for step in step_rows:
        sources = step.payload.get("discovered_competitor_sources")
        if isinstance(sources, dict) and sources:
            return sources
    return {}


def _find_competitor_profile(
    *,
    competitor_id: str,
    profiles: list[KnowledgeCompetitorResponse],
) -> KnowledgeCompetitorResponse | None:
    competitor_keys = _alias_keys_for_competitor(competitor_id)
    for profile in profiles:
        if competitor_keys.intersection(_alias_keys_for_competitor(profile.competitor_id)):
            return profile
    return None


async def _watchlist_profile_for_run(
    *,
    run_id: str | None,
    competitor_id: str,
) -> KnowledgeCompetitorResponse | None:
    if run_id is None:
        return None
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return None
        discovery_sources = await _latest_discovery_sources(session=session, run_id=run_id)
        profiles = _build_competitor_profiles(
            plan_tree=run.plan_tree,
            discovery_sources=discovery_sources,
            competitor_ids=list(run.competitors or []),
        )
    return _find_competitor_profile(competitor_id=competitor_id, profiles=profiles)


@router.get("/api/runs/{run_id}/knowledge", response_model=RunKnowledgeResponse)
async def get_run_knowledge(run_id: str) -> RunKnowledgeResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        knowledge = await load_knowledge_for_run(session=session, run_id=run_id)
        discovery_sources = await _latest_discovery_sources(session=session, run_id=run_id)
        competitors = _build_competitor_profiles(
            plan_tree=run.plan_tree,
            discovery_sources=discovery_sources,
            competitor_ids=list(run.competitors or []),
        )
        intake_draft = run.intake_draft if isinstance(run.intake_draft, dict) else {}
        analysis_archetype_raw = intake_draft.get("analysis_archetype")
        analysis_archetype = (
            analysis_archetype_raw
            if analysis_archetype_raw in {"comparison", "landscape"}
            else "comparison"
        )

    return RunKnowledgeResponse(
        run_id=run_id,
        analysis_archetype=analysis_archetype,
        schema_version=knowledge["schema_version"],
        competitors=competitors,
        features=[KnowledgeFeatureResponse.model_validate(item) for item in knowledge["features"]],
        pricings=[KnowledgePricingResponse.model_validate(item) for item in knowledge["pricings"]],
        personas=[KnowledgePersonaResponse.model_validate(item) for item in knowledge["personas"]],
        feedback=[KnowledgeFeedbackResponse.model_validate(item) for item in knowledge["feedback"]],
        missing_reasons={
            competitor_id: [
                reason for reason in reasons if isinstance(reason, str)
            ]
            for competitor_id, reasons in knowledge["missing_reasons"].items()
            if isinstance(competitor_id, str) and isinstance(reasons, list)
        },
        coverage=knowledge["coverage"],
    )


@router.get("/api/runs/{run_id}/comparisons", response_model=RunComparisonsResponse)
async def get_run_comparisons(run_id: str) -> RunComparisonsResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        items_raw = await load_comparisons_for_run(session=session, run_id=run_id)

    return RunComparisonsResponse(
        run_id=run_id,
        items=[DimensionComparisonResponse.model_validate(item) for item in items_raw],
    )


@router.get("/api/watchlist", response_model=list[WatchlistItemResponse])
async def list_watchlist() -> list[WatchlistItemResponse]:
    session_factory = get_session_factory()
    async with session_factory() as session:
        rows = (
            await session.execute(
                select(WatchlistItem).order_by(WatchlistItem.created_at.desc(), WatchlistItem.competitor_id.asc())
            )
        ).scalars().all()
    return [_to_watchlist_item(item) for item in rows]


@router.get("/api/watchlist/digest", response_model=list[WatchlistDigestItemResponse])
async def list_watchlist_digest() -> list[WatchlistDigestItemResponse]:
    session_factory = get_session_factory()
    async with session_factory() as session:
        watchlist_items = (
            await session.execute(
                select(WatchlistItem).order_by(
                    WatchlistItem.created_at.desc(),
                    WatchlistItem.competitor_id.asc(),
                )
            )
        ).scalars().all()
        if not watchlist_items:
            return []

        conclusion_rows = await session.execute(
            select(ConclusionRecord, Run.title, Run.user_query)
            .join(Run, Run.run_id == ConclusionRecord.run_id)
            .options(selectinload(ConclusionRecord.evidence_links))
            .order_by(ConclusionRecord.created_at.desc(), ConclusionRecord.conclusion_id.asc())
        )
        raw_rows = conclusion_rows.all()

        all_competitor_ids = [item.competitor_id for item in watchlist_items]
        diff_rows = (
            await session.execute(
                select(CompetitorDiff)
                .order_by(CompetitorDiff.created_at.desc())
                .limit(10 * len(all_competitor_ids))
            )
        ).scalars().all()

    diffs_by_competitor: dict[str, list[CompetitorDiffItemResponse]] = {}
    for diff in diff_rows:
        diffs_by_competitor.setdefault(
            _normalize_competitor_key(diff.competitor_id), []
        ).append(
            CompetitorDiffItemResponse(
                diff_id=diff.diff_id,
                competitor_id=diff.competitor_id,
                run_id_new=diff.run_id_new,
                run_id_old=diff.run_id_old,
                dimension=diff.dimension,
                change_type=diff.change_type,
                old_value=diff.old_value,
                new_value=diff.new_value,
                significance=diff.significance,
                created_at=diff.created_at.isoformat(),
            )
        )

    insights_by_competitor: dict[str, list[WatchInsightItemResponse]] = {}
    run_ids_by_competitor: dict[str, set[str]] = {}
    for conclusion, run_title, user_query in raw_rows:
        evidence_ids = [
            link.evidence_id
            for link in sorted(
                conclusion.evidence_links,
                key=lambda link: (link.relevance_rank, link.evidence_id),
            )
        ]
        insight = WatchInsightItemResponse(
            conclusion_id=conclusion.conclusion_id,
            run_id=conclusion.run_id,
            run_title=_resolve_run_title(title=run_title, user_query=user_query),
            section=conclusion.section,
            claim=conclusion.claim,
            confidence=conclusion.confidence,
            evidence_ids=evidence_ids,
            created_at=conclusion.created_at.isoformat(),
        )
        matched_keys: set[str] = set()
        for competitor_id in conclusion.competitor_ids:
            if not isinstance(competitor_id, str):
                continue
            matched_keys.update(_alias_keys_for_competitor(competitor_id))
        for competitor_key in matched_keys:
            insights_by_competitor.setdefault(competitor_key, []).append(insight)
            run_ids_by_competitor.setdefault(competitor_key, set()).add(conclusion.run_id)

    digest_items: list[WatchlistDigestItemResponse] = []
    for item in watchlist_items:
        competitor_keys = _alias_keys_for_competitor(item.competitor_id)
        deduped_insights: dict[str, WatchInsightItemResponse] = {}
        matched_run_ids: set[str] = set()
        for competitor_key in competitor_keys:
            matched_run_ids.update(run_ids_by_competitor.get(competitor_key, set()))
            for insight in insights_by_competitor.get(competitor_key, []):
                if insight.conclusion_id not in deduped_insights:
                    deduped_insights[insight.conclusion_id] = insight
        insights = sorted(
            deduped_insights.values(),
            key=lambda insight: (insight.created_at, insight.conclusion_id),
            reverse=True,
        )
        latest = insights[0] if insights else None
        delta = _watchlist_delta_from_insights(insights)
        recent_changes = [
            diff
            for competitor_key in competitor_keys
            for diff in diffs_by_competitor.get(competitor_key, [])
        ][:5]
        profile = (
            await _watchlist_profile_for_run(
                run_id=item.last_run_id,
                competitor_id=item.competitor_id,
            )
            or await _watchlist_profile_for_run(
                run_id=latest.run_id if latest is not None else None,
                competitor_id=item.competitor_id,
            )
        )
        digest_items.append(
            WatchlistDigestItemResponse(
                watch_id=item.watch_id,
                competitor_id=item.competitor_id,
                profile=profile,
                note=item.note,
                created_at=item.created_at.isoformat(),
                insight_count=len(insights),
                run_count=len(matched_run_ids),
                last_updated_at=latest.created_at if latest is not None else None,
                latest_run_id=latest.run_id if latest is not None else None,
                added_from_run_id=item.added_from_run_id,
                source_role=item.source_role,
                next_refresh_at=_to_iso(item.next_refresh_at),
                delta=delta,
                last_run_id=item.last_run_id,
                last_refreshed_at=_to_iso(item.last_refreshed_at),
                refresh_interval_hours=item.refresh_interval_hours,
                items=insights[:5],
                recent_changes=recent_changes,
            )
        )
    return digest_items


@router.post("/api/watchlist", response_model=WatchlistItemResponse)
async def create_watchlist_item(payload: WatchlistCreateRequest) -> WatchlistItemResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        if payload.added_from_run_id is not None:
            source_run = await session.get(Run, payload.added_from_run_id)
            if source_run is None:
                raise APIException(
                    status_code=404,
                    error_code="FROM_RUN_NOT_FOUND",
                    message=f"added_from_run_id={payload.added_from_run_id} does not exist",
                )
        existing_rows = (
            await session.execute(select(WatchlistItem).order_by(WatchlistItem.created_at.desc()))
        ).scalars().all()
        target_alias_keys = _alias_keys_for_competitor(payload.competitor_id)
        existing = next(
            (
                item
                for item in existing_rows
                if target_alias_keys.intersection(_alias_keys_for_competitor(item.competitor_id))
            ),
            None,
        )
        if existing is not None:
            raise APIException(
                status_code=409,
                error_code="WATCHLIST_ALREADY_EXISTS",
                message=(
                    f"competitor_id={payload.competitor_id} already exists in watchlist "
                    f"(matched={existing.competitor_id})"
                ),
            )
        # Seed the first auto-refresh due time when an interval is given without an
        # explicit next_refresh_at, so the scheduler (next_refresh_at <= now) picks it up.
        resolved_next_refresh_at = payload.next_refresh_at
        if resolved_next_refresh_at is None and payload.refresh_interval_hours is not None:
            resolved_next_refresh_at = datetime.now(timezone.utc) + timedelta(
                hours=payload.refresh_interval_hours
            )
        item = WatchlistItem(
            watch_id=make_id("watch_"),
            competitor_id=payload.competitor_id,
            note=payload.note,
            next_refresh_at=resolved_next_refresh_at,
            added_from_run_id=payload.added_from_run_id,
            source_role=payload.source_role,
            refresh_interval_hours=payload.refresh_interval_hours,
        )
        session.add(item)
        await session.commit()
        await session.refresh(item)
    return _to_watchlist_item(item)


@router.delete("/api/watchlist/{watch_id}", response_model=WatchlistItemResponse)
async def delete_watchlist_item(watch_id: str) -> WatchlistItemResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        item = await session.get(WatchlistItem, watch_id)
        if item is None:
            raise APIException(
                status_code=404,
                error_code="WATCHLIST_ITEM_NOT_FOUND",
                message=f"watch_id={watch_id} does not exist",
            )
        deleted_item = _to_watchlist_item(item)
        await session.delete(item)
        await session.commit()
    return deleted_item


@router.patch("/api/watchlist/{watch_id}", response_model=WatchlistItemResponse)
async def update_watchlist_item(watch_id: str, payload: WatchlistUpdateRequest) -> WatchlistItemResponse:
    session_factory = get_session_factory()
    async with session_factory() as session:
        item = await session.get(WatchlistItem, watch_id)
        if item is None:
            raise APIException(
                status_code=404,
                error_code="WATCHLIST_ITEM_NOT_FOUND",
                message=f"watch_id={watch_id} does not exist",
            )
        if "note" in payload.model_fields_set:
            normalized_note = payload.note.strip() if payload.note is not None else None
            item.note = normalized_note if normalized_note else None
        explicit_next_refresh = "next_refresh_at" in payload.model_fields_set
        if explicit_next_refresh:
            item.next_refresh_at = payload.next_refresh_at
        if "refresh_interval_hours" in payload.model_fields_set:
            item.refresh_interval_hours = payload.refresh_interval_hours
            # The scheduler scans `next_refresh_at <= now`, so setting only the
            # interval (the UI's frequency dropdown does exactly that) would leave
            # next_refresh_at NULL and the entry would never auto-refresh. Seed the
            # first due time here; clearing the interval (manual mode) stops it.
            if not explicit_next_refresh:
                if payload.refresh_interval_hours is None:
                    item.next_refresh_at = None
                else:
                    item.next_refresh_at = datetime.now(timezone.utc) + timedelta(
                        hours=payload.refresh_interval_hours
                    )
        await session.commit()
        await session.refresh(item)
    return _to_watchlist_item(item)


class WatchlistRefreshResponse(BaseModel):
    run_id: str
    watch_id: str
    status: str


@router.post("/api/watchlist/{watch_id}/refresh", response_model=WatchlistRefreshResponse)
async def manual_refresh_watchlist_item(watch_id: str, request: Request) -> WatchlistRefreshResponse:
    refresher = getattr(request.app.state, "watchlist_refresher", None)
    if refresher is None:
        raise APIException(
            status_code=503,
            error_code="REFRESHER_NOT_AVAILABLE",
            message="Watchlist refresher is not initialized.",
        )
    try:
        run_id = await refresher.trigger_single(watch_id)
    except ValueError as exc:
        raise APIException(
            status_code=404,
            error_code="WATCHLIST_ITEM_NOT_FOUND",
            message=str(exc),
        ) from exc
    return WatchlistRefreshResponse(run_id=run_id, watch_id=watch_id, status="running")


@router.get("/api/runs/{run_id}/diff", response_model=list[CompetitorDiffItemResponse])
async def get_run_diff(run_id: str) -> list[CompetitorDiffItemResponse]:
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            )
        diff_rows = (
            await session.execute(
                select(CompetitorDiff)
                .where(CompetitorDiff.run_id_new == run_id)
                .order_by(
                    case({"high": 0, "medium": 1, "low": 2}, value=CompetitorDiff.significance, else_=3),
                    CompetitorDiff.created_at.asc(),
                )
            )
        ).scalars().all()
    return [
        CompetitorDiffItemResponse(
            diff_id=diff.diff_id,
            competitor_id=diff.competitor_id,
            run_id_new=diff.run_id_new,
            run_id_old=diff.run_id_old,
            dimension=diff.dimension,
            change_type=diff.change_type,
            old_value=diff.old_value,
            new_value=diff.new_value,
            significance=diff.significance,
            created_at=diff.created_at.isoformat(),
        )
        for diff in diff_rows
    ]


@router.get("/api/runs/{run_id}/metrics", response_model=RunMetricsResponse)
async def get_run_metrics(run_id: str) -> RunMetricsResponse:
    """
    Runtime business-loop metrics for scoring and demo checkpoints.

    manual_review_rate is a proxy metric based on reviewed skill candidates
    linked to this run, not direct evaluator edits on report content.
    """

    session_factory = get_session_factory()
    async with session_factory() as session:
        try:
            snapshot = await load_run_metrics_snapshot(session=session, run_id=run_id)
        except RuntimeError as exc:
            raise APIException(
                status_code=404,
                error_code="RUN_NOT_FOUND",
                message=f"run_id={run_id} does not exist",
            ) from exc
    return RunMetricsResponse(**asdict(snapshot))


@router.get("/api/runs/{run_id}/trace", response_model=RunTraceResponse)
async def get_run_trace(run_id: str) -> RunTraceResponse:
    session_factory = get_session_factory()
    with bind_run(run_id):
        log.info("api.run.trace.query.start")
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise APIException(
                    status_code=404,
                    error_code="RUN_NOT_FOUND",
                    message=f"run_id={run_id} does not exist",
                )

            step_rows = (
                await session.execute(
                    select(Step).where(Step.run_id == run_id).order_by(Step.created_at.asc())
                )
            ).scalars().all()
            decision_rows = (
                await session.execute(
                    select(SupervisorDecisionRecord)
                    .where(SupervisorDecisionRecord.run_id == run_id)
                    .order_by(SupervisorDecisionRecord.created_at.asc())
                )
            ).scalars().all()
            llm_rows = (
                await session.execute(
                    select(LLMCall)
                    .join(Step, LLMCall.step_id == Step.step_id)
                    .where(Step.run_id == run_id)
                    .order_by(LLMCall.created_at.asc())
                )
            ).scalars().all()
        log.info(
            "api.run.trace.query.finish",
            step_count=len(step_rows),
            decision_count=len(decision_rows),
            llm_call_count=len(llm_rows),
        )

    return RunTraceResponse(
        run=_to_run_detail(run),
        steps=[_to_step_trace_response(step) for step in step_rows],
        supervisor_decisions=[
            _to_supervisor_decision_trace_response(decision) for decision in decision_rows
        ],
        llm_calls=[_to_llm_call_trace_response(llm_call) for llm_call in llm_rows],
        timeline=_build_trace_timeline(
            step_rows=list(step_rows),
            decision_rows=list(decision_rows),
            llm_rows=list(llm_rows),
        ),
    )
