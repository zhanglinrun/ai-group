from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

from core.defaults import (
    DEFAULT_DISCOVER_MAX_RESULTS,
    MAX_DISCOVERY_SEARCH_QUERIES,
    MAX_REACT_TURNS,
    MAX_RESEARCH_COMPETITORS,
)
from schemas.contracts import (
    validate_dimension,
    validate_section_id,
    validate_template_id,
    validate_token_list,
)


FocusDimension = str


class DiscoverCompetitors(BaseModel):
    """Search the web to discover competitors in a given track/domain."""
    search_queries: list[str] = Field(min_length=1, max_length=MAX_DISCOVERY_SEARCH_QUERIES)
    domain_context: str
    max_results: int = Field(default=DEFAULT_DISCOVER_MAX_RESULTS, ge=1, le=15)


class ConductResearch(BaseModel):
    research_topic: str
    competitor_id: str
    focus_dimensions: list[FocusDimension] = Field(default_factory=list)
    max_iterations: int = MAX_REACT_TURNS
    search_max_results: int = Field(default=5, ge=1, le=15)
    fallback_to_offline: bool = True

    @field_validator("focus_dimensions")
    @classmethod
    def _validate_focus_dimensions(cls, value: list[str]) -> list[str]:
        return validate_token_list(
            values=value,
            field_name="focus_dimensions",
            item_validator=validate_dimension,
            allow_empty=True,
        )


class ConductResearchBatch(BaseModel):
    topics: list[ConductResearch] = Field(min_length=1)
    parallelism_rationale: str

    @field_validator("topics")
    @classmethod
    def _cap_topics(cls, value: list[ConductResearch]) -> list[ConductResearch]:
        return value[:MAX_RESEARCH_COMPETITORS]


class Analyze(BaseModel):
    focus_dimensions: list[str] | None = None
    parallel_by_dimension: bool = False
    require_cross_competitor: bool = True

    @field_validator("focus_dimensions")
    @classmethod
    def _validate_focus_dimensions(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        return validate_token_list(
            values=value,
            field_name="focus_dimensions",
            item_validator=validate_dimension,
        )


class Write(BaseModel):
    template_id: str | None = None
    sections: list[str] | None = None
    qa_reasons: list[str] = Field(default_factory=list)
    unsupported_numeric_claims: list[dict[str, object]] = Field(default_factory=list)

    @field_validator("template_id")
    @classmethod
    def _validate_template_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        return validate_template_id(value)

    @field_validator("sections")
    @classmethod
    def _validate_sections(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        return validate_token_list(
            values=value,
            field_name="sections",
            item_validator=validate_section_id,
        )

    @field_validator("qa_reasons")
    @classmethod
    def _cap_qa_reasons(cls, value: list[str]) -> list[str]:
        return [item.strip() for item in value if item.strip()][:8]

    @field_validator("unsupported_numeric_claims")
    @classmethod
    def _cap_unsupported_numeric_claims(
        cls,
        value: list[dict[str, object]],
    ) -> list[dict[str, object]]:
        return value[:12]


class Finalize(BaseModel):
    completion_reason: Literal[
        "all_dimensions_covered",
        "max_iterations_hit",
        "fallback_path",
        "user_requested_stop",
    ]
    notes: str | None = None


class SupervisorDecision(BaseModel):
    id: str
    run_id: str
    iteration: int
    chosen_tool: Literal["DiscoverCompetitors", "ConductResearch", "ConductResearchBatch", "Analyze", "Write", "Finalize"]
    tool_args: dict
    reasoning_summary: str
    triggered_by: Literal[
        "user_query",
        "researcher_completion",
        "analyst_completion",
        "writer_completion",
        "qa_rejection",
        "qa_approval",
        "iteration_advance",
    ] | None = None
    outcome: Literal["dispatched", "rejected_by_qa", "succeeded", "failed"] | None = None
    outcome_recorded_at: str | None = None
    created_at: str
