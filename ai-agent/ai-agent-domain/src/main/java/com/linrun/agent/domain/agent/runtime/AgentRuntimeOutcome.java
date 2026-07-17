package com.linrun.agent.domain.agent.runtime;

/**
 * Result returned to the application boundary after one runtime invocation.
 * {@code ownsRunSideEffects} is true only for the caller that acquired the
 * durable NEW claim; duplicate/replay requests must not repeat memory writes.
 */
public record AgentRuntimeOutcome(String answer, boolean ownsRunSideEffects) {

    public AgentRuntimeOutcome {
        answer = answer == null ? "" : answer;
    }

    public static AgentRuntimeOutcome executed(String answer) {
        return new AgentRuntimeOutcome(answer, true);
    }

    public static AgentRuntimeOutcome notExecuted(String answer) {
        return new AgentRuntimeOutcome(answer, false);
    }
}
