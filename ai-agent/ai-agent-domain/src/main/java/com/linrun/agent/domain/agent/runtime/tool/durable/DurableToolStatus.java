package com.linrun.agent.domain.agent.runtime.tool.durable;

/** Durable lifecycle states for an externally executed tool invocation. */
public enum DurableToolStatus {
    SCHEDULED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCEL_REQUESTED,
    CANCELLED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT
                || this == CANCELLED || this == UNKNOWN;
    }
}
