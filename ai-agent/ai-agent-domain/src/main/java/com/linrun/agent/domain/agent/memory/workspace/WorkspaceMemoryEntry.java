package com.linrun.agent.domain.agent.memory.workspace;

import java.time.Instant;

/** Tenant- and owner-scoped durable Workspace Memory entry. */
public record WorkspaceMemoryEntry(String id,
                                   String tenantId,
                                   String ownerId,
                                   String topic,
                                   String content,
                                   WorkspaceMemorySource source,
                                   double confidence,
                                   long revision,
                                   long createdAtEpochMillis,
                                   Long expiresAtEpochMillis) {

    public WorkspaceMemoryEntry {
        id = required(id, "id");
        tenantId = required(tenantId, "tenantId");
        ownerId = required(ownerId, "ownerId");
        topic = required(topic, "topic");
        content = required(content, "content");
        source = source == null ? WorkspaceMemorySource.EXPLICIT_USER : source;
        confidence = Math.max(0D, Math.min(1D, confidence));
        revision = Math.max(1L, revision);
        createdAtEpochMillis = createdAtEpochMillis <= 0 ? Instant.now().toEpochMilli() : createdAtEpochMillis;
    }

    public boolean isExpired(long nowEpochMillis) {
        return expiresAtEpochMillis != null && expiresAtEpochMillis <= nowEpochMillis;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
