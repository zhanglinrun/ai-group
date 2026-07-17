package com.linrun.agent.domain.agent.runtime.tool.dispatch;

import lombok.Data;
import lombok.experimental.Accessors;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

/** Canonical result shared by tool execution, memory, events, evidence and the ledger. */
@Data
@Accessors(chain = true)
public class ToolExecutionOutcome {

    private static final String REUSED_OBSERVATION_PREFIX =
            "[Reused prior successful result for the identical tool operation; execution was skipped.]\n";

    private boolean success;
    private String toolResult;
    private String llmObservation;
    private ToolStructuredOutput structuredOutput;
    private String errorMsg;
    private boolean reused;
    /** Input parsing/schema failures may be superseded by a later corrected call. */
    private boolean correctableInputFailure;

    public static ToolExecutionOutcome success(String toolResult,
                                               String llmObservation,
                                               ToolStructuredOutput structuredOutput) {
        return new ToolExecutionOutcome()
                .setSuccess(true)
                .setToolResult(toolResult)
                .setLlmObservation(llmObservation)
                .setStructuredOutput(structuredOutput);
    }

    public static ToolExecutionOutcome failure(String toolResult,
                                               String llmObservation,
                                               ToolStructuredOutput structuredOutput,
                                               String errorMsg) {
        return new ToolExecutionOutcome()
                .setSuccess(false)
                .setToolResult(toolResult)
                .setLlmObservation(llmObservation)
                .setStructuredOutput(structuredOutput)
                .setErrorMsg(errorMsg);
    }

    public static ToolExecutionOutcome inputFailure(String toolResult,
                                                     String llmObservation,
                                                     String errorMsg) {
        return failure(toolResult, llmObservation, null, errorMsg)
                .setCorrectableInputFailure(true);
    }

    /** Create a shallow run-local reuse view without mutating the original outcome. */
    public static ToolExecutionOutcome reusedFrom(ToolExecutionOutcome source) {
        if (source == null || !source.isSuccess()) {
            throw new IllegalArgumentException("Only a successful outcome can be reused");
        }
        return new ToolExecutionOutcome()
                .setSuccess(true)
                .setToolResult(source.getToolResult())
                .setLlmObservation(REUSED_OBSERVATION_PREFIX
                        + (source.getLlmObservation() == null ? "" : source.getLlmObservation()))
                .setStructuredOutput(source.getStructuredOutput())
                .setReused(true);
    }
}
