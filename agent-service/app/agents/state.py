from __future__ import annotations

import operator
from typing import Annotated, Literal, TypedDict

from schemas.intake import IntakeClarifyRequest, IntakeExchange, RunIntakeDraft
from schemas.plan import FollowUpRequest, PlanTree
from schemas.supervisor import SupervisorDecision


def _last_write_wins(_: object, new: object) -> object:
    return new


RunPhase = Literal["intake", "planning", "executing", "done"]


class AgentState(TypedDict, total=False):
    run_id: str
    user_query: str
    domain_hint: str | None
    market_scope: str | None
    response_language: str | None
    reference_urls: list[str]
    competitors: Annotated[list[str], operator.add]
    discovered_competitors: Annotated[list[str], operator.add]
    discovered_competitor_sources: Annotated[dict[str, dict[str, str | None]], _last_write_wins]
    current_iteration: int
    pending_tool_args: Annotated[dict[str, object], _last_write_wins]
    next_action: Literal["discovery", "researcher", "analyst", "writer", "finalize"]
    last_completed_node: Annotated[
        Literal["researcher", "analyst", "writer"] | None,
        _last_write_wins,
    ]
    qa_outcome: Literal["approved", "rejected", "force_degraded"] | None
    qa_reject_to: Literal["researcher", "analyst", "writer", "supervisor"] | None
    qa_rejection_count: int
    pending_review_target_step_id: str | None
    qa_reasons: list[str]
    qa_degrade_reason: str | None
    qa_degraded_required_sections: list[str]
    qa_unsupported_numeric_claims: list[dict[str, object]]
    qa_numeric_claim_blocklist: list[dict[str, object]]
    researched_competitors: Annotated[list[str], operator.add]
    researcher_degraded_competitors: Annotated[list[str], operator.add]
    analysis_done: bool
    report_draft_done: bool
    report_renderable_sections: list[str]
    report_degraded_required_sections: list[str]
    replan_count: int
    decisions: list[SupervisorDecision]
    status: Annotated[str, _last_write_wins]
    # User-facing terminal explanation; written on Finalize (degraded) and
    # persisted onto runs.status_reason by the graph finalizer.
    status_reason: Annotated[str | None, _last_write_wins]

    # --- Phase 1+ Agent-native intake + plan-then-execute (contract; nodes TBD) ---
    # `phase` drives the conditional entry route (Invariant B). Legacy runs omit it
    # and fall through to `supervisor` for backward compatibility.
    phase: RunPhase
    # True only on the chat-intake handoff path: intake is complete but the user
    # still needs to pick a depth profile before planner_generate runs.
    report_depth_selection_pending: bool
    intake_draft: RunIntakeDraft
    intake_history: list[IntakeExchange]
    plan_tree: PlanTree | None
    follow_up_queue: Annotated[list[FollowUpRequest], operator.add]
    # Invariant A cross-node carriers: written by the *_generate_node (which commits),
    # read+interrupted by the *_wait_node. NOT a single-node "skip LLM" cache.
    pending_clarify: IntakeClarifyRequest | None
    pending_plan_tree: PlanTree | None


ACCUMULATING_STATE_FIELDS: tuple[str, ...] = (
    "competitors",
    "discovered_competitors",
    "researched_competitors",
    "researcher_degraded_competitors",
    "follow_up_queue",
)


def spread_without_accumulators(state: AgentState) -> dict[str, object]:
    """Copy graph state without operator.add fields that would be re-applied as deltas."""
    return {key: value for key, value in state.items() if key not in ACCUMULATING_STATE_FIELDS}
