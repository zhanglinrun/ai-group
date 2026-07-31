package com.linrun.agent.domain.agent.runtime.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Deterministic fallback for local runs without MySQL and unit-contract tests. */
public final class InMemoryContextSnapshotRepository implements ContextSnapshotRepository {

    private final Map<ContextSnapshotKey, ContextSnapshot> snapshots = new LinkedHashMap<>();

    @Override
    public synchronized Optional<ContextSnapshot> find(ContextSnapshotKey key) {
        return Optional.ofNullable(snapshots.get(key));
    }

    @Override
    public synchronized Optional<ContextSnapshot> compareAndSet(ContextSnapshot next, long expectedRevision) {
        ContextSnapshot current = snapshots.get(next.key());
        long actualRevision = current == null ? 0 : current.revision();
        if (actualRevision != expectedRevision || next.revision() != expectedRevision + 1) {
            return Optional.empty();
        }
        snapshots.put(next.key(), next);
        return Optional.of(next);
    }
}
