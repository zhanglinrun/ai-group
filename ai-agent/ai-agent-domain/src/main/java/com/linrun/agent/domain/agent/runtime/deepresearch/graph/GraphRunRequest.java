package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** The business request supplied to a graph adapter without framework-specific state. */
public record GraphRunRequest(AgentContext context, AgentRequest request) {

    public GraphRunRequest {
        Objects.requireNonNull(context, "AgentContext must not be null");
        Objects.requireNonNull(request, "AgentRequest must not be null");
    }

    public static GraphRunRequest from(AgentContext context, AgentRequest request) {
        return new GraphRunRequest(context, request);
    }

    public String threadId() {
        return stableThreadId(request.getOwnerId(), request.getRequestId());
    }

    public static String stableThreadId(String ownerId, String requestId) {
        String identity = String.valueOf(ownerId) + ":" + String.valueOf(requestId);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
