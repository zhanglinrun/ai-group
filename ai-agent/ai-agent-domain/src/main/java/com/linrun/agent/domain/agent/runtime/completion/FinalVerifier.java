package com.linrun.agent.domain.agent.runtime.completion;

/** Independent, read-only verifier invoked before the unified loop is allowed to stop. */
@FunctionalInterface
public interface FinalVerifier {
    CompletionDecision verify(CompletionRequest request);

    /**
     * Cheap verifiers may run for every profile. A future token-consuming verifier can
     * override this method to restrict itself to the profiles/tasks that justify its cost.
     */
    default boolean supports(CompletionRequest request) {
        return true;
    }
}
