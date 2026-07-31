package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, framework-neutral identity for one execution admission.
 * P30 replaces the provisional fencing token with the durable lease token.
 */
public record AgentRunContext(String tenantId,
                              long userId,
                              String sessionId,
                              long runId,
                              String requestId,
                              Instant deadline,
                              long fencingToken) {

    public AgentRunContext {
        tenantId = requireText(tenantId, "tenantId");
        sessionId = requireText(sessionId, "sessionId");
        requestId = requireText(requestId, "requestId");
        deadline = Objects.requireNonNull(deadline, "deadline must not be null");
    }

    public static AgentRunContext from(AgentContext context) {
        Objects.requireNonNull(context, "AgentContext must not be null");
        AgentRunState state = context.getAgentRunState();
        return new AgentRunContext(
                blankToDefault(context.getTenantId(), "default"),
                context.getOwnerId() == null ? 0L : context.getOwnerId(),
                blankToDefault(context.getSessionId(), "request:" + context.getRequestId()),
                state == null || state.getRunId() == null ? 0L : state.getRunId(),
                requireText(context.getRequestId(), "requestId"),
                Objects.requireNonNull(context.getRunDeadlineAt(), "run deadline must be activated before harness use"),
                context.getFencingToken() == null ? 0L : context.getFencingToken()
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
