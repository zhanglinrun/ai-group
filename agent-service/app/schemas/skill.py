from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class SkillCandidate(BaseModel):
    id: str
    candidate_type: Literal["qa_rule", "prompt_template", "source_routing"]
    applies_to: Literal["qa_rule", "prompt_template", "source_routing"]
    tags: list[str] = Field(default_factory=list)
    payload: dict[str, Any]
    rationale: str
    supporting_run_ids: list[str] = Field(default_factory=list)
    confidence: Literal["low", "medium", "high"]
    status: Literal["staging", "approved", "rejected"] = "staging"
    reviewed_by: str | None = None
    reviewed_at: str | None = None
    error: str | None = None
    created_at: str


class QARuleCandidatePayload(BaseModel):
    rule_yaml: str
    triggered_failures_count: int
    similar_existing_rules: list[str] = Field(default_factory=list)


class PromptTemplateCandidatePayload(BaseModel):
    target_agent: Literal["supervisor", "researcher", "analyst", "writer", "qa"]
    template_name: str
    template_body: str
    replaces_template_id: str | None = None
    evidence_quality_delta: float
    rejection_rate_delta: float


class SourceRoutingCandidatePayload(BaseModel):
    source_type: str
    competitor_category: str
    priority_delta: int
    quality_score_sample: list[float] = Field(default_factory=list)
