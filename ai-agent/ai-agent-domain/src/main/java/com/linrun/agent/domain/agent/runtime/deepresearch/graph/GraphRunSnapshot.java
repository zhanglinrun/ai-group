package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Framework-neutral checkpoint summary; it never stores prompts or hidden reasoning. */
public record GraphRunSnapshot(String graphId,
                               String threadId,
                               String status,
                               boolean terminal,
                               Instant observedAt,
                               Map<String, Object> checkpointState) {

    /** Compatibility constructor for callers that only need checkpoint metadata. */
    public GraphRunSnapshot(String graphId,
                            String threadId,
                            String status,
                            boolean terminal,
                            Instant observedAt) {
        this(graphId, threadId, status, terminal, observedAt, Map.of());
    }

    public GraphRunSnapshot {
        Objects.requireNonNull(graphId, "graphId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        observedAt = observedAt == null ? Instant.now() : observedAt;
        checkpointState = checkpointState == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(checkpointState));
    }
}
