package com.linrun.agent.domain.agent.runtime.context;

import java.util.Optional;

/** Durable snapshot store. A failed compare-and-set must never overwrite a newer revision. */
public interface ContextSnapshotRepository {

    Optional<ContextSnapshot> find(ContextSnapshotKey key);

    Optional<ContextSnapshot> compareAndSet(ContextSnapshot next, long expectedRevision);
}
