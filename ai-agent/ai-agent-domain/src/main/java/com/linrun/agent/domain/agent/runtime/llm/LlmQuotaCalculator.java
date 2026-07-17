package com.linrun.agent.domain.agent.runtime.llm;

/** Integer microcredit arithmetic for one LLM call. */
public final class LlmQuotaCalculator {

    public static final int MIN_OUTPUT_TOKENS = 256;

    private LlmQuotaCalculator() {
    }

    public static ReservationAmounts reservation(int inputTokens,
                                                 int requestedOutputTokens,
                                                 long inputRate,
                                                 long outputRate) {
        requireRates(inputRate, outputRate);
        long inputCharge = charge(inputTokens, 0, inputRate, outputRate);
        return new ReservationAmounts(
                Math.addExact(inputCharge, Math.multiplyExact((long) requestedOutputTokens, outputRate)),
                Math.addExact(inputCharge, Math.multiplyExact((long) MIN_OUTPUT_TOKENS, outputRate)));
    }

    public static int affordableOutputTokens(long reservedMicrocredits,
                                             int inputTokens,
                                             int requestedOutputTokens,
                                             long inputRate,
                                             long outputRate) {
        requireRates(inputRate, outputRate);
        long inputCharge = Math.multiplyExact((long) inputTokens, inputRate);
        long outputBudget = Math.max(0L, reservedMicrocredits - inputCharge);
        return Math.toIntExact(Math.min(requestedOutputTokens, outputBudget / outputRate));
    }

    public static long charge(int inputTokens, int outputTokens, long inputRate, long outputRate) {
        requireRates(inputRate, outputRate);
        return Math.addExact(
                Math.multiplyExact(Math.max(0L, inputTokens), inputRate),
                Math.multiplyExact(Math.max(0L, outputTokens), outputRate));
    }

    public static void requireWithinReservation(long chargedMicrocredits,
                                                long reservedMicrocredits,
                                                String scene) {
        if (chargedMicrocredits > reservedMicrocredits) {
            throw new IllegalStateException("Provider usage exceeded the exact " + scene
                    + " reservation: charged=" + chargedMicrocredits + ", reserved=" + reservedMicrocredits);
        }
    }

    private static void requireRates(long inputRate, long outputRate) {
        if (inputRate <= 0 || outputRate <= 0) {
            throw new IllegalArgumentException("LLM input/output rates must be greater than zero");
        }
    }

    public record ReservationAmounts(long requestedMicrocredits, long minimumMicrocredits) {
    }
}
