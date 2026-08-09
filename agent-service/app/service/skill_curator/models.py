from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class SkillCuratorCandidate(BaseModel):
    candidate_type: Literal["qa_rule", "prompt_template", "source_routing"]
    tags: list[str] = Field(default_factory=list)
    payload: dict[str, object]
    rationale: str
    confidence: Literal["low", "medium", "high"] = "medium"
    supporting_run_ids: list[str] = Field(default_factory=list)


class SkillCuratorOutput(BaseModel):
    candidates: list[SkillCuratorCandidate] = Field(default_factory=list)
