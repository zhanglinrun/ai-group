from __future__ import annotations

from agents.nodes.planner import (
    _guard_plan_rationale,
    _guard_plan_tasks_for_research_mode,
)
from schemas.plan import PlanTask


def test_academic_plan_removes_commercial_dimensions_and_copy() -> None:
    tasks = [
        PlanTask(
            stage="research",
            title="Competitor pricing review",
            description="Compare features, pricing, and user feedback.",
            competitor_id="BioLAMR",
            focus_dimensions=["feature", "pricing", "user_feedback"],
        )
    ]

    guarded = _guard_plan_tasks_for_research_mode(
        tasks,
        research_mode="academic",
        response_language="en",
        max_dimensions=6,
    )

    assert guarded[0].focus_dimensions == ["methods", "datasets", "benchmarks", "limitations"]
    assert guarded[0].title == "Paper Review: BioLAMR"
    assert "pricing" not in guarded[0].description.casefold()
    assert "market" not in _guard_plan_rationale(
        "Compare vendor pricing and market size.", research_mode="academic"
    ).casefold()
