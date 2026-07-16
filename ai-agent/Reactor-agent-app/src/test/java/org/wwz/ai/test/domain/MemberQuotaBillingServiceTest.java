package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.quota.MemberQuotaFeignClient;
import org.wwz.ai.application.agent.quota.MemberQuotaResult;
import org.wwz.ai.application.agent.quota.QuotaFreezeVO;
import org.wwz.ai.application.agent.quota.QuotaInsufficientException;

/**
 * member-service 配额预扣链路测试。
 */
public class MemberQuotaBillingServiceTest {

    @Test(expected = QuotaInsufficientException.class)
    public void shouldRejectWhenQuotaInsufficient() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingService service = new MemberQuotaBillingService(client);
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(621);
        result.setMessage("配额不足");
        Mockito.when(client.freeze(Mockito.any())).thenReturn(result);

        service.reserve(1001L, 10_000L, 10_000L, "req:llm:1");
    }

    @Test
    public void shouldReserveUpToAvailableAndSettleActualAmount() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingService service = new MemberQuotaBillingService(client);
        MemberQuotaResult<QuotaFreezeVO> freezeResult = new MemberQuotaResult<>();
        freezeResult.setCode(200);
        freezeResult.setData(QuotaFreezeVO.builder().freezeId("call-1").amount(25_000L).build());
        MemberQuotaResult<Void> confirmResult = new MemberQuotaResult<>();
        confirmResult.setCode(200);
        Mockito.when(client.freeze(Mockito.any())).thenReturn(freezeResult);
        Mockito.when(client.confirm(Mockito.any())).thenReturn(confirmResult);

        var reservation = service.reserve(1001L, 100_000L, 10_000L, "req:llm:1");
        service.settle(reservation.freezeId(), 12_345L);

        Assert.assertEquals(25_000L, reservation.reservedMicrocredits());
        Mockito.verify(client).freeze(Mockito.argThat(request ->
                request.getAmount() == 100_000L && request.getMinAmount() == 10_000L));
        Mockito.verify(client).confirm(Mockito.argThat(request ->
                request.getActualAmount() == 12_345L));
    }

    @Test
    public void shouldStopAtNextCallBoundaryWhenSecondReservationIsRejected() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingService service = new MemberQuotaBillingService(client);
        MemberQuotaResult<QuotaFreezeVO> first = new MemberQuotaResult<>();
        first.setCode(200);
        first.setData(QuotaFreezeVO.builder().freezeId("call-1").amount(20_000L).build());
        MemberQuotaResult<QuotaFreezeVO> second = new MemberQuotaResult<>();
        second.setCode(621);
        second.setMessage("配额不足");
        Mockito.when(client.freeze(Mockito.any())).thenReturn(first, second);

        service.reserve(1001L, 20_000L, 10_000L, "req:llm:1");
        try {
            service.reserve(1001L, 20_000L, 10_000L, "req:llm:2");
            Assert.fail("second LLM call should be rejected before provider invocation");
        } catch (QuotaInsufficientException expected) {
            Assert.assertEquals("配额不足", expected.getMessage());
        }

        Mockito.verify(client, Mockito.times(2)).freeze(Mockito.any());
    }
}
