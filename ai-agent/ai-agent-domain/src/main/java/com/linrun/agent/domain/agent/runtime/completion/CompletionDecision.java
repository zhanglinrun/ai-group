package com.linrun.agent.domain.agent.runtime.completion;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Typed stop-gate decision returned to the agent loop. */
@Value
@Builder
public class CompletionDecision {
    boolean canStop;
    @Builder.Default
    List<String> reasons = List.of();
    @Builder.Default
    List<String> requiredActions = List.of();
    @Builder.Default
    boolean verifierExecuted = false;

    public static CompletionDecision allow(boolean verifierExecuted) {
        return CompletionDecision.builder()
                .canStop(true)
                .verifierExecuted(verifierExecuted)
                .build();
    }

    public String toFeedbackMessage() {
        StringBuilder feedback = new StringBuilder("Completion gate rejected the attempted final answer.");
        if (!reasons.isEmpty()) {
            feedback.append("\nReasons:\n- ").append(String.join("\n- ", reasons));
        }
        if (!requiredActions.isEmpty()) {
            feedback.append("\nRequired actions:\n- ").append(String.join("\n- ", requiredActions));
        }
        feedback.append("\nContinue from the current todo state. Do not recreate completed work.");
        return feedback.toString();
    }
}
