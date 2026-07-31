package com.linrun.agent.domain.agent.adapter.port;

/** Per-call quota boundary implemented in production by the durable settlement coordinator. */
public interface QuotaBillingPort {

    Reservation reserve(Long userId, long requestedMicrocredits, long minimumMicrocredits, String requestId);

    /** Reserve quota with an explicit ledger ability code for billable tools. */
    default Reservation reserve(Long userId,
                                long requestedMicrocredits,
                                long minimumMicrocredits,
                                String abilityCode,
                                String requestId) {
        return reserve(userId, requestedMicrocredits, minimumMicrocredits, requestId);
    }

    /**
     * Reserves quota while binding the durable command to the active distributed trace.
     * Implementations that predate trace correlation keep their existing behavior through
     * the compatibility default; the production coordinator persists the value.
     */
    default Reservation reserve(Long userId,
                                long requestedMicrocredits,
                                long minimumMicrocredits,
                                String abilityCode,
                                String requestId,
                                String traceId) {
        return reserve(userId, requestedMicrocredits, minimumMicrocredits, abilityCode, requestId);
    }

    void settle(String freezeId, long actualMicrocredits);

    void release(String freezeId);

    /**
     * Must be durably recorded immediately before the external provider is invoked.
     * Authenticated production calls fail closed when this transition cannot be stored.
     */
    default void markProviderStarted(String freezeId) {
        // Test/dummy ports may omit durable tracking. The production coordinator overrides this.
    }

    /** Terminal confirm plus an audit snapshot independent of the LLM invocation finalizer. */
    default SettlementResult settleWithUsage(String freezeId,
                                             long actualMicrocredits,
                                             UsageMetadata usageMetadata) {
        return settleWithStatus(freezeId, actualMicrocredits);
    }

    /** Terminal release plus an audit snapshot independent of the LLM invocation finalizer. */
    default SettlementResult releaseWithUsage(String freezeId, UsageMetadata usageMetadata) {
        return releaseWithStatus(freezeId);
    }

    /** Idempotent terminal apply that reports the member-side state. */
    default SettlementResult settleWithStatus(String freezeId, long actualMicrocredits) {
        settle(freezeId, actualMicrocredits);
        return new SettlementResult(freezeId, ReservationState.CONFIRMED, actualMicrocredits);
    }

    /** Idempotent terminal apply that reports the member-side state. */
    default SettlementResult releaseWithStatus(String freezeId) {
        release(freezeId);
        return new SettlementResult(freezeId, ReservationState.RELEASED, 0L);
    }

    /** Resolve an ambiguous reserve response by the original idempotency key. */
    default ReservationStatus findByRequest(Long userId, String requestId) {
        return new ReservationStatus(
                null, userId, 0L, 0L, ReservationState.NOT_FOUND, requestId);
    }

    default ReservationStatus findByFreezeId(String freezeId) {
        return new ReservationStatus(
                freezeId, null, 0L, 0L, ReservationState.NOT_FOUND, null);
    }

    record Reservation(String freezeId, long reservedMicrocredits) {
    }

    enum ReservationState {
        PENDING,
        CONFIRMED,
        RELEASED,
        NOT_FOUND,
        UNKNOWN;

        public static ReservationState resolve(String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    record ReservationStatus(String freezeId,
                             Long userId,
                             long reservedMicrocredits,
                             long settledMicrocredits,
                             ReservationState state,
                             String requestId) {
    }

    record SettlementResult(String freezeId,
                            ReservationState state,
                            long settledMicrocredits) {
    }

    record UsageMetadata(Long llmInvocationId,
                         Long inputRateSnapshot,
                         Long outputRateSnapshot,
                         Integer promptTokens,
                         Integer completionTokens,
                         String usageSource,
                         long chargedMicrocredits) {
    }
}
