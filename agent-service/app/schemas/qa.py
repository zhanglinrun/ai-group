from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class RetryPolicy(BaseModel):
    max_retry: int
    current_retry: int = 0
    fallback_action: Literal["finalize_degraded", "skip"] = "finalize_degraded"


class Rejection(BaseModel):
    rejection_id: str
    step_id: str
    reject_to: Literal["supervisor", "researcher", "analyst", "writer"]
    failed_rule_ids: list[str] = Field(default_factory=list)
    warning_rule_ids: list[str] = Field(default_factory=list)
    semantic_findings: list[str] = Field(default_factory=list)
    remediation_hints: dict[str, str] = Field(default_factory=dict)
    required_fields: list[str] = Field(default_factory=list)
    retry_policy: RetryPolicy
    severity: Literal["blocking", "warning"]
    reviewer_step_id: str
    created_at: str


class Approval(BaseModel):
    approval_id: str
    step_id: str
    passed_rule_ids: list[str] = Field(default_factory=list)
    warning_rule_ids: list[str] = Field(default_factory=list)
    semantic_audit_passed: bool
    reviewer_step_id: str
    created_at: str
