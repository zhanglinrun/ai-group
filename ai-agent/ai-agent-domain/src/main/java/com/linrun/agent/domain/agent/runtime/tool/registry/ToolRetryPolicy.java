package com.linrun.agent.domain.agent.runtime.tool.registry;

/** Explicit retry policy recorded in tool metadata; no implicit retry is allowed. */
public enum ToolRetryPolicy {
    NONE,
    TRANSIENT_ONLY
}
