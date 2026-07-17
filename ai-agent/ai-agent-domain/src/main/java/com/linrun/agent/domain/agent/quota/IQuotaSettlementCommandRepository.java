package com.linrun.agent.domain.agent.quota;

import java.util.List;

/** Persistence boundary for the agent-owned billing command log. */
public interface IQuotaSettlementCommandRepository {

    boolean insertIfAbsent(QuotaSettlementCommand command);

    QuotaSettlementCommand findByUserAndBillingRequestId(Long userId, String billingRequestId);

    QuotaSettlementCommand findByFreezeId(String freezeId);

    boolean markReserved(Long id, int version, String freezeId, long reservedMicrocredits);

    boolean markReserveFailed(Long id, int version, String lastError);

    boolean markProviderStarted(Long id,
                                int version,
                                long manualReviewMinutes);

    boolean persistIntent(Long id,
                          int version,
                          QuotaSettlementIntent intent,
                          long intendedMicrocredits,
                          Long llmInvocationId,
                          Long inputRateSnapshot,
                          Long outputRateSnapshot,
                          Integer promptTokens,
                          Integer completionTokens,
                          String usageSource,
                          long chargedMicrocredits);

    boolean markTerminal(Long id,
                         int version,
                         QuotaSettlementState terminalState,
                         long settledMicrocredits);

    boolean markConflict(Long id, int version, String lastError);

    boolean markManualReview(Long id, int version, String lastError);

    boolean scheduleRetry(Long id,
                          int version,
                          long retryDelaySeconds,
                          String lastError);

    List<QuotaSettlementCommand> claimDue(String leaseOwner,
                                          long leaseSeconds,
                                          int batchLimit);
}
