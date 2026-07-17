package com.linrun.agent.domain.agent.runtime.llm;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;

/** Applies a resolved usage decision to the member-service reservation. */
public final class LlmQuotaSettlementExecutor {

    private LlmQuotaSettlementExecutor() {
    }

    public static void apply(QuotaBillingPort billingPort,
                             QuotaBillingPort.Reservation reservation,
                             LlmUsageSettlement.Result usage) {
        apply(billingPort, reservation, usage,
                new QuotaBillingPort.UsageMetadata(
                        null, null, null,
                        usage == null ? null : usage.inputTokens(),
                        usage == null ? null : usage.outputTokens(),
                        usage == null ? null : usage.usageSource(),
                        usage == null ? 0L : usage.chargedMicrocredits()));
    }

    public static void apply(QuotaBillingPort billingPort,
                             QuotaBillingPort.Reservation reservation,
                             LlmUsageSettlement.Result usage,
                             QuotaBillingPort.UsageMetadata usageMetadata) {
        if (billingPort == null || reservation == null || usage == null) {
            return;
        }
        if (!usage.billable()) {
            billingPort.releaseWithUsage(reservation.freezeId(), usageMetadata);
            return;
        }
        LlmQuotaCalculator.requireWithinReservation(
                usage.chargedMicrocredits(), reservation.reservedMicrocredits(), "LLM call");
        billingPort.settleWithUsage(
                reservation.freezeId(), usage.chargedMicrocredits(), usageMetadata);
    }
}
