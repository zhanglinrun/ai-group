package com.linrun.agent.domain.agent.runtime.completion;

import lombok.Builder;
import lombok.Value;

/** Structured, run-local evidence produced by one tool call. */
@Value
@Builder
public class ToolExecutionEvidence {
    String toolCallId;
    String toolName;
    String operationKey;
    boolean success;
    String errorMessage;

    /** True only for model-correctable JSON/schema input rejection. */
    boolean correctableInputFailure;

    /** Todo item active when the call began; null for pre-Todo or legacy evidence. */
    Integer todoStepIndex;

    /** Activation identity paired with {@link #todoStepIndex}. */
    Long todoStepActivationId;

    /** Replayed operation-ledger results are observable but cannot prove new work. */
    boolean reused;
}
