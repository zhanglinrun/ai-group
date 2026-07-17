package com.linrun.agent.infrastructure.adapter.repository;

import com.linrun.agent.domain.agent.quota.IQuotaSettlementCommandRepository;
import com.linrun.agent.domain.agent.quota.QuotaSettlementCommand;
import com.linrun.agent.domain.agent.quota.QuotaSettlementIntent;
import com.linrun.agent.domain.agent.quota.QuotaSettlementState;
import com.linrun.agent.infrastructure.dao.reactor.IQuotaSettlementCommandDao;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class QuotaSettlementCommandRepository implements IQuotaSettlementCommandRepository {

    private final IQuotaSettlementCommandDao dao;

    @Override
    public boolean insertIfAbsent(QuotaSettlementCommand command) {
        try {
            return dao.insert(command) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public QuotaSettlementCommand findByUserAndBillingRequestId(Long userId, String billingRequestId) {
        return dao.findByUserAndBillingRequestId(userId, billingRequestId);
    }

    @Override
    public QuotaSettlementCommand findByFreezeId(String freezeId) {
        return dao.findByFreezeId(freezeId);
    }

    @Override
    public boolean markReserved(Long id, int version, String freezeId, long reservedMicrocredits) {
        return dao.markReserved(id, version, freezeId, reservedMicrocredits) == 1;
    }

    @Override
    public boolean markReserveFailed(Long id, int version, String lastError) {
        return dao.markReserveFailed(id, version, lastError) == 1;
    }

    @Override
    public boolean markProviderStarted(Long id,
                                       int version,
                                       long manualReviewMinutes) {
        return dao.markProviderStarted(id, version, manualReviewMinutes) == 1;
    }

    @Override
    public boolean persistIntent(Long id,
                                 int version,
                                 QuotaSettlementIntent intent,
                                 long intendedMicrocredits,
                                 Long llmInvocationId,
                                 Long inputRateSnapshot,
                                 Long outputRateSnapshot,
                                 Integer promptTokens,
                                 Integer completionTokens,
                                 String usageSource,
                                 long chargedMicrocredits) {
        return dao.persistIntent(id, version, intent, intendedMicrocredits,
                llmInvocationId, inputRateSnapshot, outputRateSnapshot,
                promptTokens, completionTokens, usageSource, chargedMicrocredits) == 1;
    }

    @Override
    public boolean markTerminal(Long id,
                                int version,
                                QuotaSettlementState terminalState,
                                long settledMicrocredits) {
        return dao.markTerminal(id, version, terminalState, settledMicrocredits) == 1;
    }

    @Override
    public boolean markConflict(Long id, int version, String lastError) {
        return dao.markConflict(id, version, lastError) == 1;
    }

    @Override
    public boolean markManualReview(Long id, int version, String lastError) {
        return dao.markManualReview(id, version, lastError) == 1;
    }

    @Override
    public boolean scheduleRetry(Long id, int version, long retryDelaySeconds, String lastError) {
        return dao.scheduleRetry(id, version, retryDelaySeconds, lastError) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<QuotaSettlementCommand> claimDue(String leaseOwner,
                                                 long leaseSeconds,
                                                 int batchLimit) {
        dao.claimDue(leaseOwner, leaseSeconds, batchLimit);
        return dao.findByLeaseOwner(leaseOwner);
    }
}
