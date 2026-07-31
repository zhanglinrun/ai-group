package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.quota.QuotaRemoteCallException;
import com.linrun.agent.infrastructure.gateway.quota.MemberQuotaBillingAdapter;
import com.linrun.agent.infrastructure.gateway.quota.MemberQuotaFeignClient;
import com.linrun.agent.infrastructure.gateway.quota.dto.MemberQuotaResult;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeRequest;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeVO;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Raw member-service gateway tests. Durable state transitions are covered separately. */
public class MemberQuotaBillingAdapterTest {

    @Test(expected = QuotaInsufficientException.class)
    public void shouldRejectWhenQuotaInsufficient() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(621);
        result.setMessage("配额不足");
        Mockito.when(client.freeze(Mockito.any())).thenReturn(result);

        adapter.reserveRemote(1001L, 10_000L, 10_000L, "llm_call", "req:llm:1");
    }

    @Test
    public void shouldAuthenticateCompleteSnapshotAfterCompactFreezeResponse() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        Mockito.when(client.freeze(Mockito.any())).thenReturn(success(QuotaFreezeVO.builder()
                .freezeId("call-1").amount(25_000L).build()));
        Mockito.when(client.findByRequest(1001L, "req:llm:1", "trace-p130-1")).thenReturn(success(pendingSnapshot(
                "call-1", 1001L, 100_000L, 10_000L, 25_000L, "llm_call", "req:llm:1")));

        var status = adapter.reserveRemote(1001L, 100_000L, 10_000L,
                "llm_call", "req:llm:1", "trace-p130-1");

        Assert.assertEquals(25_000L, status.reservedMicrocredits());
        Assert.assertEquals(QuotaBillingPort.ReservationState.PENDING, status.state());
        Mockito.verify(client).freeze(Mockito.argThat(request ->
                request.getAmount() == 100_000L
                        && request.getMinAmount() == 10_000L
                        && "trace-p130-1".equals(request.getTraceId())
                        && "ai-agent".equals(request.getOwnerService())));
    }

    @Test
    public void shouldHashLongIdempotencyKeyForFreezeAndLookup() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        String longRequestId = "r".repeat(64) + ":llm:9223372036854775807";
        Mockito.when(client.freeze(Mockito.any())).thenReturn(success(QuotaFreezeVO.builder()
                .freezeId("call-long").amount(20_000L).build()));
        Mockito.when(client.findByRequest(Mockito.eq(1001L), Mockito.anyString(), Mockito.isNull()))
                .thenAnswer(invocation -> success(pendingSnapshot(
                        "call-long", 1001L, 20_000L, 10_000L, 20_000L,
                        "llm_call", invocation.getArgument(1))));

        adapter.reserveRemote(1001L, 20_000L, 10_000L, "llm_call", longRequestId);

        ArgumentCaptor<QuotaFreezeRequest> captor = ArgumentCaptor.forClass(QuotaFreezeRequest.class);
        Mockito.verify(client).freeze(captor.capture());
        Assert.assertEquals(64, captor.getValue().getRequestId().length());
        Assert.assertTrue(captor.getValue().getRequestId().matches("[0-9a-f]{64}"));
        Mockito.verify(client).findByRequest(1001L, captor.getValue().getRequestId(), null);
    }

    @Test
    public void shouldMapSuccessfulMissingLookupToExplicitNotFound() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        Mockito.when(client.findByFreezeId("missing", null, null)).thenReturn(success(null));

        var status = adapter.findByFreezeIdRemote("missing");

        Assert.assertNotNull(status);
        Assert.assertEquals(QuotaBillingPort.ReservationState.NOT_FOUND, status.state());
        Assert.assertEquals("missing", status.freezeId());
    }

    @Test
    public void shouldReturnConfirmedSnapshot() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        QuotaFreezeVO confirmed = pendingSnapshot(
                "call-1", 1001L, 100_000L, 10_000L, 25_000L, "llm_call", "req:llm:1");
        confirmed.setStatus("CONFIRMED");
        confirmed.setSettledAmount(12_345L);
        Mockito.when(client.confirm(Mockito.any())).thenReturn(success(confirmed));

        var status = adapter.confirmRemote("call-1", 12_345L);

        Assert.assertEquals(QuotaBillingPort.ReservationState.CONFIRMED, status.state());
        Assert.assertEquals(12_345L, status.settledMicrocredits());
    }

    @Test(expected = QuotaRemoteCallException.class)
    public void shouldNotMisclassifyReleaseServiceFailureAsInsufficientQuota() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        MemberQuotaResult<QuotaFreezeVO> releaseResult = new MemberQuotaResult<>();
        releaseResult.setCode(500);
        releaseResult.setMessage("release rejected");
        Mockito.when(client.release(Mockito.any())).thenReturn(releaseResult);

        adapter.releaseRemote("freeze-1");
    }

    @Test(expected = QuotaRemoteCallException.class)
    public void shouldTreatReserveServiceFailureAsRecoverableRemoteFailure() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingAdapter adapter = new MemberQuotaBillingAdapter(client);
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(503);
        result.setMessage("member temporarily unavailable");
        Mockito.when(client.freeze(Mockito.any())).thenReturn(result);

        adapter.reserveRemote(1001L, 10_000L, 10_000L, "llm_call", "req:llm:503");
    }

    private static MemberQuotaResult<QuotaFreezeVO> success(QuotaFreezeVO data) {
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(200);
        result.setData(data);
        return result;
    }

    private static QuotaFreezeVO pendingSnapshot(String freezeId,
                                                 Long userId,
                                                 long requested,
                                                 long minimum,
                                                 long reserved,
                                                 String ability,
                                                 String requestId) {
        return QuotaFreezeVO.builder()
                .freezeId(freezeId)
                .userId(userId)
                .amount(reserved)
                .settledAmount(0L)
                .requestedAmount(requested)
                .minAmount(minimum)
                .abilityCode(ability)
                .status("PENDING")
                .requestId(requestId)
                .requestFingerprint("fingerprint")
                .ownerService("ai-agent")
                .build();
    }
}
