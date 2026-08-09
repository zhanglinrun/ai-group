from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

AgentName = Literal["supervisor", "researcher", "analyst", "writer", "qa", "skill_curator"]
AgentMessageStatus = Literal[
    "pending",
    "running",
    "completed",
    "rejected",
    "approved",
    "degraded",
    "failed",
]


class AgentMessage(BaseModel):
    message_id: str
    run_id: str
    step_id: str
    trace_id: str
    source_agent: AgentName
    target_agent: AgentName
    status: AgentMessageStatus
    payload_type: str
    payload: dict[str, Any]
    evidence_refs: list[str] = Field(default_factory=list)
    artifact_refs: list[str] = Field(default_factory=list)
    created_at: str
