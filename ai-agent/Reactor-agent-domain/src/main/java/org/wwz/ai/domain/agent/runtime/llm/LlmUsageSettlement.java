package org.wwz.ai.domain.agent.runtime.llm;

/** Resolves authoritative provider usage or the local estimation fallback. */
public final class LlmUsageSettlement {

    private LlmUsageSettlement() {
    }

    public static Result resolve(Integer providerInput,
                                 Integer providerOutput,
                                 int estimatedInput,
                                 int estimatedOutput,
                                 long inputRate,
                                 long outputRate) {
        // Some OpenAI-compatible streaming providers expose a Usage object whose
        // prompt/completion counters are both zero. That is an unavailable usage
        // signal, not an authoritative free invocation; fall back to the bounded
        // local estimate so a successful call cannot silently settle at zero.
        boolean provider = providerInput != null && providerInput > 0
                && providerOutput != null && providerOutput >= 0;
        int inputTokens = provider ? providerInput : estimatedInput;
        int outputTokens = provider ? providerOutput : estimatedOutput;
        long calculated = LlmQuotaCalculator.charge(inputTokens, outputTokens, inputRate, outputRate);
        return new Result(inputTokens, outputTokens, provider ? "PROVIDER" : "ESTIMATED", calculated);
    }

    public record Result(int inputTokens,
                         int outputTokens,
                         String usageSource,
                         long chargedMicrocredits) {
    }
}
