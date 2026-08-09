from __future__ import annotations

from pydantic import ValidationError

from agents.state import AgentState
from schemas.intake import IntakeExchange, RunIntakeDraft
from schemas.plan import PlanTree
from schemas.supervisor import SupervisorDecision


def coerce_plan_tree(value: object) -> PlanTree | None:
    """Normalize plan_tree from LangGraph checkpoint, API dict, or in-memory model."""
    if value is None:
        return None
    if isinstance(value, PlanTree):
        return value
    if isinstance(value, dict):
        return PlanTree.model_validate(value)
    raise TypeError(f"plan_tree must be PlanTree | dict | None, got {type(value).__name__}")


def require_plan_tree(state: AgentState) -> PlanTree:
    plan = coerce_plan_tree(state.get("plan_tree"))
    if plan is None:
        raise RuntimeError("AgentState.plan_tree is required but missing or invalid.")
    return plan


def coerce_pending_plan_tree(state: AgentState) -> PlanTree:
    pending = coerce_plan_tree(state.get("pending_plan_tree"))
    if pending is None:
        raise RuntimeError(
            "planner_wait_node entered without pending_plan_tree in state; check graph wiring."
        )
    return pending


def coerce_intake_draft(state: AgentState) -> RunIntakeDraft:
    draft = state.get("intake_draft")
    if isinstance(draft, RunIntakeDraft):
        return draft
    if isinstance(draft, dict):
        return RunIntakeDraft.model_validate(draft)
    raise RuntimeError("AgentState.intake_draft is required but missing or invalid.")


def coerce_intake_draft_or_default(state: AgentState) -> RunIntakeDraft:
    draft = state.get("intake_draft")
    if isinstance(draft, RunIntakeDraft):
        return draft
    if isinstance(draft, dict):
        return RunIntakeDraft.model_validate(draft)
    user_query = state.get("user_query") or ""
    return RunIntakeDraft(user_query=user_query)


def coerce_intake_history(state: AgentState) -> list[IntakeExchange]:
    raw = state.get("intake_history") or []
    out: list[IntakeExchange] = []
    for item in raw:
        if isinstance(item, IntakeExchange):
            out.append(item)
            continue
        if isinstance(item, dict):
            try:
                out.append(IntakeExchange.model_validate(item))
            except ValidationError:
                continue
    return out


def coerce_supervisor_decisions(state: AgentState) -> list[SupervisorDecision]:
    raw = state.get("decisions") or []
    out: list[SupervisorDecision] = []
    for item in raw:
        if isinstance(item, SupervisorDecision):
            out.append(item)
            continue
        if isinstance(item, dict):
            try:
                out.append(SupervisorDecision.model_validate(item))
            except ValidationError:
                continue
    return out
