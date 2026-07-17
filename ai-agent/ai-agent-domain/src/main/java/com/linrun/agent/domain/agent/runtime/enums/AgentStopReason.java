package com.linrun.agent.domain.agent.runtime.enums;

/** Typed terminal reason for one Agent Loop run. */
public enum AgentStopReason {
    NONE,
    COMPLETED,
    MAX_TURNS,
    REPEATED_TURN,
    DOWNSTREAM_ABORTED,
    TOOL_CALL_BUDGET,
    TIME_BUDGET,
    TOKEN_BUDGET,
    CREDIT_BUDGET,
    COMPLETION_ATTEMPT_BUDGET,
    REQUIRED_CAPABILITY_UNAVAILABLE,
    MODEL_MAX_TOKENS,
    MODEL_REFUSAL,
    MODEL_CONTENT_FILTER,
    MODEL_STOP_REASON_UNSUPPORTED,
    MODEL_ERROR,
    EXECUTION_ERROR
}
