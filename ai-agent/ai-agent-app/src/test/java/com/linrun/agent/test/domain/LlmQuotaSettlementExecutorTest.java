package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.runtime.llm.LlmQuotaSettlementExecutor;
import com.linrun.agent.domain.agent.runtime.llm.LlmUsageSettlement;
import org.junit.Test;
import org.mockito.Mockito;

public class LlmQuotaSettlementExecutorTest {

    @Test
    public void shouldReleaseReservationForRejectedProviderCallWithoutUsageEvidence() {
        QuotaBillingPort billingPort = Mockito.mock(QuotaBillingPort.class);
        QuotaBillingPort.Reservation reservation =
                new QuotaBillingPort.Reservation("freeze-401", 100_000L);
        LlmUsageSettlement.Result usage = LlmUsageSettlement.resolveFailure(
                null, null, 3_205, 0, 5L, 30L, false);

        LlmQuotaSettlementExecutor.apply(billingPort, reservation, usage);

        Mockito.verify(billingPort).releaseWithUsage(
                Mockito.eq("freeze-401"),
                Mockito.argThat(metadata -> metadata.chargedMicrocredits() == 0L
                        && "UNAVAILABLE".equals(metadata.usageSource())));
        Mockito.verify(billingPort, Mockito.never()).settleWithUsage(
                Mockito.anyString(), Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void shouldSettleEstimatedUsageWhenProviderEmittedPartialOutputBeforeFailure() {
        QuotaBillingPort billingPort = Mockito.mock(QuotaBillingPort.class);
        QuotaBillingPort.Reservation reservation =
                new QuotaBillingPort.Reservation("freeze-partial", 100_000L);
        LlmUsageSettlement.Result usage = LlmUsageSettlement.resolveFailure(
                null, null, 900, 80, 5L, 30L, true);

        LlmQuotaSettlementExecutor.apply(billingPort, reservation, usage);

        Mockito.verify(billingPort).settleWithUsage(
                Mockito.eq("freeze-partial"),
                Mockito.eq(6_900L),
                Mockito.argThat(metadata -> metadata.promptTokens() == 900
                        && metadata.completionTokens() == 80
                        && metadata.chargedMicrocredits() == 6_900L));
        Mockito.verify(billingPort, Mockito.never()).releaseWithUsage(
                Mockito.anyString(), Mockito.any());
    }
}
