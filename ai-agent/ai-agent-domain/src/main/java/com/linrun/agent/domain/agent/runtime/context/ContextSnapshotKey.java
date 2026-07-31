package com.linrun.agent.domain.agent.runtime.context;

/** Immutable tenant/owner/run identity used for snapshot reads and CAS writes. */
public record ContextSnapshotKey(String tenantId, String ownerId, String sessionId, long runId) {

    public ContextSnapshotKey {
        tenantId = required(tenantId, "tenantId");
        ownerId = required(ownerId, "ownerId");
        sessionId = required(sessionId, "sessionId");
        if (runId < 0) {
            throw new IllegalArgumentException("runId must not be negative");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
