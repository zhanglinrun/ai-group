package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import java.util.Optional;
import java.util.Map;

/** Adapter-owned checkpoint lookup/recording boundary. */
public interface GraphCheckpointPort {

    Optional<GraphRunSnapshot> find(String graphId, String threadId);

    void save(GraphRunSnapshot snapshot);

    /**
     * Recoverable graph projection only. Implementations must not persist expanded
     * prompts, raw model output, hidden reasoning, or an AgentContext instance.
     */
    default Optional<Map<String, Object>> findCheckpointState(String graphId, String threadId) {
        return find(graphId, threadId).map(GraphRunSnapshot::checkpointState);
    }
}
