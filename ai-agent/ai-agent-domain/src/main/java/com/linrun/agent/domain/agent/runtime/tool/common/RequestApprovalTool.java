package com.linrun.agent.domain.agent.runtime.tool.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalGate;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalDecision;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit model-facing approval request; execution still fails closed. */
public class RequestApprovalTool implements BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AgentContext agentContext;

    public void setAgentContext(AgentContext agentContext) {
        this.agentContext = agentContext;
    }

    @Override
    public String getName() {
        return "request_approval";
    }

    @Override
    public String getDescription() {
        return "为明确的外部副作用请求发起用户审批；未获批准时不会执行副作用。";
    }

    @Override
    public Map<String, Object> toParams() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string"),
                        "reason", Map.of("type", "string"),
                        "estimated_microcredits", Map.of("type", "integer", "minimum", 0)),
                "required", List.of("action", "reason"),
                "additionalProperties", false);
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof Map<?, ?> raw)) {
            return failure("request_approval input must be an object");
        }
        String action = text(raw.get("action"));
        String reason = text(raw.get("reason"));
        if (action.isBlank() || reason.isBlank()) {
            return failure("action and reason are required");
        }
        if (agentContext == null || agentContext.getOwnerId() == null
                || agentContext.getOwnerId() <= 0L
                || agentContext.getRuntimeDependencies() == null
                || agentContext.getRuntimeDependencies().getApprovalGate() == null) {
            return failure("authenticated approval service is unavailable");
        }
        long estimate = number(raw.get("estimated_microcredits"));
        ApprovalGate.ApprovalResult result = agentContext.getRuntimeDependencies().getApprovalGate()
                .awaitApproval(ApprovalGate.ApprovalRequest.builder()
                                .runId(agentContext.getRequestId())
                                .ownerId(String.valueOf(agentContext.getOwnerId()))
                                .toolCallId("request_approval")
                                .toolName(action)
                                .argumentsJson(toJson(raw))
                                .estimatedMicrocredits(estimate)
                                .approvalRequired(true)
                                .build(),
                        approval -> { },
                        agentContext::isDownstreamAborted);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", result.isApproved());
        output.put("approval_id", result.getApprovalId());
        output.put("decision", result.getDecision() == null ? null : result.getDecision().name());
        output.put("reason", result.getReason());
        try {
            String serialized = MAPPER.writeValueAsString(output);
            return result.isApproved()
                    ? ToolResultPayload.text(serialized)
                    : ToolResultPayload.failure(serialized, serialized, null,
                    result.getReason() == null ? ApprovalDecision.REJECTED.name() : result.getReason());
        } catch (Exception error) {
            return failure("approval result serialization failed");
        }
    }

    @Override
    public boolean isConcurrencySafe(Object input) {
        return false;
    }

    private long number(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            return "{\"redacted\":true}";
        }
    }

    private ToolResultPayload failure(String message) {
        return ToolResultPayload.failure(message, message, null, message);
    }
}
