package com.linrun.agent.domain.agent.runtime.enums;

/**
 * Evidence contract declared for one Todo item.
 *
 * <p>{@link #LEGACY} is never accepted from a new DEEP todo_write request. It
 * exists only so snapshots written before per-step policies were introduced
 * can still be replayed and verified with the historical rules.</p>
 */
public enum TodoEvidencePolicy {

    /** Cognitive/control-plane work that must not consume business-tool evidence. */
    NONE,

    /** Work that must be proven by a successful, non-reused tool call in this activation. */
    TOOL,

    /** Backward-compatible policy for snapshots that predate explicit declarations. */
    LEGACY
}
