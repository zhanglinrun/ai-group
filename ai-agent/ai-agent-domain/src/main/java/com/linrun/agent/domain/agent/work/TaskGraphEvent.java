package com.linrun.agent.domain.agent.work;

import java.time.Instant;
import java.util.Map;

/** Durable control-plane event used by the Work UI and replay/audit consumers. */
public record TaskGraphEvent(
        String eventUid,
        String workspaceId,
        String taskId,
        String eventType,
        String actorId,
        Map<String, Object> payload,
        Instant createdAt
) {
    public TaskGraphEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
