package com.linrun.agent.infrastructure.dao.reactor;

import com.linrun.agent.domain.agent.quota.QuotaSettlementCommand;
import com.linrun.agent.domain.agent.quota.QuotaSettlementIntent;
import com.linrun.agent.domain.agent.quota.QuotaSettlementState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IQuotaSettlementCommandDao {

    int insert(QuotaSettlementCommand command);

    QuotaSettlementCommand findByUserAndBillingRequestId(@Param("userId") Long userId,
                                                         @Param("billingRequestId") String billingRequestId);

    QuotaSettlementCommand findByFreezeId(@Param("freezeId") String freezeId);

    int markReserved(@Param("id") Long id,
                     @Param("version") int version,
                     @Param("freezeId") String freezeId,
                     @Param("reservedMicrocredits") long reservedMicrocredits);

    int markReserveFailed(@Param("id") Long id,
                          @Param("version") int version,
                          @Param("lastError") String lastError);

    int markProviderStarted(@Param("id") Long id,
                            @Param("version") int version,
                            @Param("manualReviewMinutes") long manualReviewMinutes);

    int persistIntent(@Param("id") Long id,
                      @Param("version") int version,
                      @Param("intent") QuotaSettlementIntent intent,
                      @Param("intendedMicrocredits") long intendedMicrocredits,
                      @Param("llmInvocationId") Long llmInvocationId,
                      @Param("inputRateSnapshot") Long inputRateSnapshot,
                      @Param("outputRateSnapshot") Long outputRateSnapshot,
                      @Param("promptTokens") Integer promptTokens,
                      @Param("completionTokens") Integer completionTokens,
                      @Param("usageSource") String usageSource,
                      @Param("chargedMicrocredits") long chargedMicrocredits);

    int markTerminal(@Param("id") Long id,
                     @Param("version") int version,
                     @Param("terminalState") QuotaSettlementState terminalState,
                     @Param("settledMicrocredits") long settledMicrocredits);

    int markConflict(@Param("id") Long id,
                     @Param("version") int version,
                     @Param("lastError") String lastError);

    int markManualReview(@Param("id") Long id,
                         @Param("version") int version,
                         @Param("lastError") String lastError);

    int scheduleRetry(@Param("id") Long id,
                      @Param("version") int version,
                      @Param("retryDelaySeconds") long retryDelaySeconds,
                      @Param("lastError") String lastError);

    int claimDue(@Param("leaseOwner") String leaseOwner,
                 @Param("leaseSeconds") long leaseSeconds,
                 @Param("batchLimit") int batchLimit);

    List<QuotaSettlementCommand> findByLeaseOwner(@Param("leaseOwner") String leaseOwner);
}
