from __future__ import annotations

from agents.nodes.planner import reconcile_plan_tree_after_discovery
from agents.state import AgentState
from agents.state_coercion import (
    coerce_intake_draft_or_default,
    coerce_intake_history,
    coerce_plan_tree,
)
from schemas.intake import IntakeExchange, IntakeClarifyRequest, IntakeUserReply
from schemas.plan import PlanTask, PlanTree


def test_coerce_plan_tree_accepts_model_and_dict() -> None:
    plan = PlanTree(
        tasks=[PlanTask(stage="discover", title="discover", description="discover")],
        version=1,
    )
    assert coerce_plan_tree(plan) is plan
    assert coerce_plan_tree(plan.model_dump()) == plan


def test_reconcile_plan_tree_uses_coerced_plan_tree_model() -> None:
    plan = PlanTree(
        tasks=[
            PlanTask(stage="discover", title="发现竞品", description="discover"),
            PlanTask(stage="analyze", title="分析", description="analyze"),
            PlanTask(stage="write", title="撰写", description="write"),
        ],
        version=1,
    )
    reconciled = reconcile_plan_tree_after_discovery(
        plan_tree=plan,
        discovered_competitors=["Notion", "Cursor"],
    )
    research_tasks = [task for task in reconciled.tasks if task.stage == "research"]
    assert len(research_tasks) == 2


def test_coerce_intake_draft_or_default_from_dict() -> None:
    state: AgentState = {
        "intake_draft": {"user_query": "compare tools", "analysis_intent": "pricing"},
    }
    draft = coerce_intake_draft_or_default(state)
    assert draft.analysis_intent == "pricing"


def test_coerce_intake_history_skips_invalid_entries() -> None:
    clarify = IntakeClarifyRequest(question="role?", field_targets=["user_role"])
    reply = IntakeUserReply(text="PM", selected_options=[])
    state: AgentState = {
        "intake_history": [
            IntakeExchange(clarify=clarify, reply=reply),
            {"invalid": True},
        ],
    }
    history = coerce_intake_history(state)
    assert len(history) == 1
    assert history[0].reply.text == "PM"
