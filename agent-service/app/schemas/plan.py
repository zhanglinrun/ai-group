from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

from schemas.contracts import normalize_dimensions
from schemas.ids import make_id

PlanTaskStage = Literal["discover", "research", "analyze", "write"]
PlanTaskSource = Literal["agent", "user"]
PlanTaskPriority = Literal["normal", "user_pinned"]


class PlanTask(BaseModel):
    """One executable unit in the Agent's proposed plan, shown in the Plan Tree."""

    task_id: str = Field(default_factory=lambda: make_id("ptask_"))
    stage: PlanTaskStage
    title: str
    description: str = ""
    competitor_id: str | None = None
    focus_dimensions: list[str] = Field(default_factory=list)
    source: PlanTaskSource = "agent"
    enabled: bool = True
    priority: PlanTaskPriority = "normal"

    @field_validator("focus_dimensions")
    @classmethod
    def _normalize_focus_dimensions(cls, value: list[str]) -> list[str]:
        return normalize_dimensions(value, allow_empty=True)


class PlanTree(BaseModel):
    """The Agent's full proposed plan; `version` bumps on each user edit.

    `confirmed_at` is the lifecycle signal: None when freshly published by
    planner_generate, ISO timestamp after planner_wait resumes from the user's
    confirmation. _derive_run_phase reads this to distinguish "planning"
    (paused at planner_wait) from "executing" without poking graph state.
    """

    plan_id: str = Field(default_factory=lambda: make_id("plan_"))
    tasks: list[PlanTask] = Field(default_factory=list)
    rationale: str = ""
    version: int = 1
    confirmed_at: str | None = None
    competitor_sources: dict[str, dict[str, str | None]] = Field(default_factory=dict)


class PlanDepthSelectRequest(BaseModel):
    """Resume payload for the depth-selection interrupt before planning."""

    report_depth: Literal["debug", "quick", "deep"]


class PlanConfirmRequest(BaseModel):
    """Resume payload for the plan-confirm interrupt."""

    disabled_task_ids: list[str] = Field(default_factory=list)
    # Phase ?: user-injected tasks (forced priority="user_pinned" by the planner node).
    additional_tasks: list[PlanTask] = Field(default_factory=list)


class FollowUpRequest(BaseModel):
    """Phase 4 wire payload: mid-run user addendum from POST /follow-up."""

    text: str = Field(min_length=1, max_length=1000)
    applies_to_stage: PlanTaskStage | None = None


class FollowUpEntry(BaseModel):
    """Phase 4 storage form persisted under `runs.follow_ups` JSONB.

    The supervisor reads entries with `consumed_at is None` at the start of
    each iteration, injects them into its prompt, then marks them consumed.
    A new entry is appended (not replaced) per POST /follow-up so the user
    can stack multiple addenda between supervisor turns.
    """

    id: str = Field(default_factory=lambda: make_id("fu_"))
    text: str
    applies_to_stage: PlanTaskStage | None = None
    received_at: str
    consumed_at: str | None = None
    consumed_in_iteration: int | None = None
