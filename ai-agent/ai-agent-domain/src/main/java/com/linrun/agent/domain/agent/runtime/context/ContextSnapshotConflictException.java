package com.linrun.agent.domain.agent.runtime.context;

/** Signals an optimistic-lock conflict instead of silently replacing a newer summary. */
public class ContextSnapshotConflictException extends RuntimeException {

    public ContextSnapshotConflictException(ContextSnapshotKey key, long expectedRevision) {
        super("context snapshot revision conflict: key=" + key + ", expectedRevision=" + expectedRevision);
    }
}
