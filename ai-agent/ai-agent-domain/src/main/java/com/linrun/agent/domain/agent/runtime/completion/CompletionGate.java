package com.linrun.agent.domain.agent.runtime.completion;

/** Decides whether an agent loop is allowed to emit its final answer. */
public interface CompletionGate {
    CompletionDecision evaluate(CompletionRequest request);
}
