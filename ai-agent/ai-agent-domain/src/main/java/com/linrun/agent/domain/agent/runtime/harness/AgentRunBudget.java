package com.linrun.agent.domain.agent.runtime.harness;

/** Unified, run-local limits used to bound turns, tools, time, tokens and cost. */
public record AgentRunBudget(
        int maxTurns,
        int maxToolCalls,
        int maxCompletionAttempts,
        long maxDurationMillis,
        long maxTotalTokens,
        long maxMicrocredits
) {

    public static AgentRunBudget defaults() {
        return new AgentRunBudget(10, 32, 3, 15 * 60_000L, 200_000L, 10_000_000L);
    }

    public AgentRunBudget {
        maxTurns = positive(maxTurns, 10);
        maxToolCalls = positive(maxToolCalls, 32);
        maxCompletionAttempts = positive(maxCompletionAttempts, 3);
        maxDurationMillis = positive(maxDurationMillis, 15 * 60_000L);
        maxTotalTokens = positive(maxTotalTokens, 200_000L);
        maxMicrocredits = positive(maxMicrocredits, 10_000_000L);
    }

    public AgentRunBudget withMaxTurns(int value) {
        return new AgentRunBudget(
                value,
                maxToolCalls,
                maxCompletionAttempts,
                maxDurationMillis,
                maxTotalTokens,
                maxMicrocredits
        );
    }

    public AgentRunBudget withMaxTotalTokens(long value) {
        return new AgentRunBudget(
                maxTurns,
                maxToolCalls,
                maxCompletionAttempts,
                maxDurationMillis,
                value,
                maxMicrocredits
        );
    }

    public AgentRunBudget withMaxDurationMillis(long value) {
        return new AgentRunBudget(
                maxTurns,
                maxToolCalls,
                maxCompletionAttempts,
                value,
                maxTotalTokens,
                maxMicrocredits
        );
    }

    public AgentRunBudget withMaxMicrocredits(long value) {
        return new AgentRunBudget(
                maxTurns,
                maxToolCalls,
                maxCompletionAttempts,
                maxDurationMillis,
                maxTotalTokens,
                value
        );
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
