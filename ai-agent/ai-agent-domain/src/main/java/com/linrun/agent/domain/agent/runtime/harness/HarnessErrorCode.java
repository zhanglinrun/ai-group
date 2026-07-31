package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;

/** Canonical P20 error codes shared by Standard and DEEP Harness calls. */
public enum HarnessErrorCode {
    MODEL_TIMEOUT,
    MODEL_RATE_LIMITED,
    SCHEMA_INVALID,
    TOOL_DENIED,
    TOOL_TIMEOUT,
    TOOL_UNKNOWN_SIDE_EFFECT,
    QUOTA_INSUFFICIENT,
    RUN_CANCELLED,
    RUN_OWNERSHIP_LOST,
    EXECUTION_ERROR;

    public static HarnessErrorCode from(Throwable error, AgentStopReason stopReason) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof QuotaInsufficientException) {
                return QUOTA_INSUFFICIENT;
            }
            current = current.getCause();
        }
        if (stopReason == AgentStopReason.DOWNSTREAM_ABORTED || stopReason == AgentStopReason.RUN_CANCELLED) {
            return RUN_CANCELLED;
        }
        if (stopReason == AgentStopReason.RUN_OWNERSHIP_LOST) {
            return RUN_OWNERSHIP_LOST;
        }
        if (stopReason == AgentStopReason.TIME_BUDGET) {
            return MODEL_TIMEOUT;
        }
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        if (message.contains("schema") || message.contains("json")) {
            return SCHEMA_INVALID;
        }
        if (message.contains("denied") || message.contains("forbidden") || message.contains("approval")) {
            return TOOL_DENIED;
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return TOOL_TIMEOUT;
        }
        if (message.contains("rate limit") || message.contains("429")) {
            return MODEL_RATE_LIMITED;
        }
        return EXECUTION_ERROR;
    }
}
