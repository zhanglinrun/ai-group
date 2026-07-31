package com.linrun.agent.domain.agent.runtime.tool.durable;

/** Callback CAS outcome. Duplicate callbacks are acknowledged but do not create a second terminal event. */
public enum DurableToolCallbackResult {
    ACCEPTED,
    DUPLICATE,
    FENCE_REJECTED,
    NOT_FOUND,
    INVALID_STATE
}
