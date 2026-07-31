package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/local fallback only; the production SAA graph uses JdbcGraphCheckpointPort. */
@Component
public class InMemoryGraphCheckpointPort implements GraphCheckpointPort {

    private final ConcurrentHashMap<String, GraphRunSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<GraphRunSnapshot> find(String graphId, String threadId) {
        return Optional.ofNullable(snapshots.get(key(graphId, threadId)));
    }

    @Override
    public void save(GraphRunSnapshot snapshot) {
        snapshots.put(key(snapshot.graphId(), snapshot.threadId()), snapshot);
    }

    private String key(String graphId, String threadId) {
        return graphId + ':' + threadId;
    }
}
