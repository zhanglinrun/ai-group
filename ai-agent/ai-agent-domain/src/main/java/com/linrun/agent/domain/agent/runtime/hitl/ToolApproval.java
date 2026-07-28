package com.linrun.agent.domain.agent.runtime.hitl;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder(toBuilder = true)
public class ToolApproval {
    Long id;
    String ownerId;
    String runId;
    String toolCallId;
    String toolName;
    String argumentsPreview;
    long estimatedMicrocredits;
    ApprovalDecision status;
    Instant expiresAt;
    String decisionPayload;
    Instant createdAt;
    Instant decidedAt;
}
