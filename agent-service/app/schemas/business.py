from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

from schemas.contracts import validate_source_type


class Competitor(BaseModel):
    id: str
    name: str
    website: str | None = None
    category: str
    positioning: str | None = None
    target_users: list[str] = Field(default_factory=list)
    evidence_ids: list[str] = Field(default_factory=list)


class Feature(BaseModel):
    id: str
    competitor_id: str
    name: str
    parent_id: str | None = None
    description: str | None = None
    maturity: Literal["unknown", "basic", "advanced", "leading"] | None = None
    evidence_ids: list[str] = Field(default_factory=list)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("Feature requires at least one evidence_id")
        return value


class Pricing(BaseModel):
    id: str
    competitor_id: str
    model: str
    tiers: list[dict] = Field(default_factory=list)
    free_plan: bool | None = None
    enterprise_plan: bool | None = None
    evidence_ids: list[str] = Field(default_factory=list)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("Pricing requires at least one evidence_id")
        return value


class Persona(BaseModel):
    id: str
    competitor_id: str
    name: str
    role: str
    pain_points: list[str] = Field(default_factory=list)
    jobs_to_be_done: list[str] = Field(default_factory=list)
    evidence_ids: list[str] = Field(default_factory=list)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("Persona requires at least one evidence_id")
        return value


class UserFeedback(BaseModel):
    id: str
    competitor_id: str
    sentiment: Literal["positive", "neutral", "negative", "mixed"]
    topic: str
    summary: str
    evidence_ids: list[str] = Field(default_factory=list)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("UserFeedback requires at least one evidence_id")
        return value


class Evidence(BaseModel):
    id: str
    run_id: str
    source_type: str
    source_url: str | None = None
    source_title: str | None = None
    quote: str
    sanitized_text: str
    span: dict | None = None
    collected_by: str
    collected_at: str
    desensitized: bool

    @field_validator("source_type")
    @classmethod
    def _validate_source_type(cls, value: str) -> str:
        return validate_source_type(value)


class Conclusion(BaseModel):
    id: str
    section: str
    claim: str
    confidence: Literal["low", "medium", "high"]
    competitor_ids: list[str] = Field(default_factory=list)
    evidence_ids: list[str] = Field(default_factory=list)
    risk_flags: list[str] = Field(default_factory=list)

    @field_validator("evidence_ids")
    @classmethod
    def validate_evidence_ids(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("Conclusion requires at least one evidence_id")
        return value


class CompetitorKnowledgeFragment(BaseModel):
    schema_version: str = "schema_v0.2"
    run_id: str
    competitor_id: str
    researcher_step_id: str
    competitor: Competitor
    features: list[Feature] = Field(default_factory=list)
    pricings: list[Pricing] = Field(default_factory=list)
    feedback: list[UserFeedback] = Field(default_factory=list)
    coverage: dict[str, str] = Field(default_factory=dict)
    notes: str | None = None


class CompetitorKnowledgeAggregate(BaseModel):
    schema_version: str = "schema_v0.2"
    run_id: str
    domain_hint: str | None = None
    fragments: list[CompetitorKnowledgeFragment] = Field(default_factory=list)
    personas: list[Persona] = Field(default_factory=list)
    coverage_summary: dict[str, str] = Field(default_factory=dict)
