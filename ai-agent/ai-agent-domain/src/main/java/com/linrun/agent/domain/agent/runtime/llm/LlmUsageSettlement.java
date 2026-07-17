package com.linrun.agent.domain.agent.runtime.llm;

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
        boolean providerInputAvailable = providerInput != null && providerInput > 0;
        boolean providerOutputAvailable = providerOutput != null && providerOutput >= 0
                && (providerOutput > 0 || providerInputAvailable);
        int inputTokens = providerInputAvailable ? providerInput : estimatedInput;
        int outputTokens = providerOutputAvailable ? providerOutput : estimatedOutput;
        long calculated = LlmQuotaCalculator.charge(inputTokens, outputTokens, inputRate, outputRate);
        String usageSource = providerInputAvailable && providerOutputAvailable
                ? "PROVIDER"
                : (providerInputAvailable || providerOutputAvailable ? "MIXED" : "ESTIMATED");
        return new Result(inputTokens, outputTokens, usageSource, calculated, true);
    }

    /**
     * Failure settlement must not turn a local input estimate into a real charge.
     * A rejected request such as an upstream 401 has no trustworthy provider usage
     * and no output evidence, so its reservation is released and the invocation is
     * recorded with zero usage. If provider counters or a real partial output were
     * observed before a later failure, the call remains billable and auditable.
     */
    public static Result resolveFailure(Integer providerInput,
                                        Integer providerOutput,
                                        int estimatedInput,
                                        int estimatedOutput,
                                        long inputRate,
                                        long outputRate,
                                        boolean providerActivityObserved) {
        boolean providerUsageAvailable = (providerInput != null && providerInput > 0)
                || (providerOutput != null && providerOutput > 0);
        if (!providerUsageAvailable && !providerActivityObserved) {
            return new Result(0, 0, "UNAVAILABLE", 0L, false);
        }
        return resolve(providerInput, providerOutput, estimatedInput, estimatedOutput, inputRate, outputRate);
    }

    public record Result(int inputTokens,
                         int outputTokens,
                         String usageSource,
                         long chargedMicrocredits,
                         boolean billable) {
    }
}
