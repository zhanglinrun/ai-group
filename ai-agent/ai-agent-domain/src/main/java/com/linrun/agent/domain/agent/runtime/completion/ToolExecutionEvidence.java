package com.linrun.agent.domain.agent.runtime.completion;

import lombok.Builder;
import lombok.Value;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

/** Structured, run-local evidence produced by one tool call. */
@Value
@Builder
public class ToolExecutionEvidence {
    String toolCallId;
    String toolName;
    String operationKey;
    boolean success;
    String errorMessage;

    /** Raw successful tool result retained only for evidence extraction inside the active run. */
    String toolResult;

    /** Typed result kept run-local so P90 can distinguish candidates from fetched evidence. */
    ToolStructuredOutput structuredOutput;

    /** True only for model-correctable JSON/schema input rejection. */
    boolean correctableInputFailure;

    /** Todo item active when the call began; null for pre-Todo or legacy evidence. */
    Integer todoStepIndex;

    /** Activation identity paired with {@link #todoStepIndex}. */
    Long todoStepActivationId;

    /** Replayed operation-ledger results are observable but cannot prove new work. */
    boolean reused;
}
