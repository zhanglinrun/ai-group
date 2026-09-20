package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.adapter.reconciliation.BenefitReconciliationService;
import com.aigroup.paymall.infrastructure.dao.IBenefitEventDao;
import com.aigroup.paymall.infrastructure.dao.IBenefitReconciliationDao;
import com.aigroup.paymall.infrastructure.dao.po.BenefitEvent;
import com.aigroup.paymall.infrastructure.dao.po.BenefitReconciliation;
import com.aigroup.paymall.infrastructure.gateway.IMemberCatalogService;
import com.aigroup.paymall.infrastructure.gateway.dto.MemberBenefitStatusDTO;
import com.aigroup.paymall.infrastructure.gateway.response.MemberResult;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class BenefitReconciliationServiceTest {
    private IBenefitEventDao events;
    private IBenefitReconciliationDao outcomes;
    private IOrderRepository orders;
    private IMemberCatalogService member;
    private IBenefitEventPort publisher;
    private BenefitReconciliationService service;

    @Before
    public void setUp() {
        events = mock(IBenefitEventDao.class);
        outcomes = mock(IBenefitReconciliationDao.class);
        orders = mock(IOrderRepository.class);
        member = mock(IMemberCatalogService.class);
        publisher = mock(IBenefitEventPort.class);
        service = new BenefitReconciliationService(events, outcomes, orders, member, publisher);
        when(outcomes.claim(anyString(), anyString())).thenReturn(1);
        when(outcomes.finish(anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(1);
        when(outcomes.recordReplayIntent(anyString(), anyString(), eq(3))).thenReturn(1);
        when(outcomes.findByEventId(anyString())).thenReturn(reconciliation(0));
    }

    @Test
    public void pendingReplaysOriginalPayloadThenGrantedNeedsNoFurtherReplay() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"), status("GRANTED"));

        assertEquals(1, service.reconcilePublishedGrants());
        ArgumentCaptor<TradeCompletedEvent> sent = ArgumentCaptor.forClass(TradeCompletedEvent.class);
        verify(publisher).publishTradeCompleted(sent.capture());
        assertEquals("e-1", sent.getValue().getEventId());
        assertEquals("GROUP_BUY_COMPLETED", sent.getValue().getEventType());
        assertEquals("o-1", sent.getValue().getOrderId());
        assertEquals(Long.valueOf(60), sent.getValue().getBaseQuota());
        verify(outcomes).recordReplayIntent(eq("e-1"), anyString(), eq(3));
        verify(outcomes).finish(eq("e-1"), anyString(), eq("REPLAYED"), eq("MARKET"),
                eq("PENDING"), eq("AWAITING_MEMBER"), eq(5));

        assertEquals(1, service.reconcilePublishedGrants());
        verify(outcomes).finish(eq("e-1"), anyString(), eq("GRANTED"), eq("MARKET"),
                eq("GRANTED"), eq("CONFIRMED"), isNull());
        verify(publisher, times(1)).publishTradeCompleted(any(TradeCompletedEvent.class));
    }

    @Test
    public void duplicateRunWithLostClaimCannotSendTwice() {
        candidate("e-1", "o-1", OrderStatusVO.DEAL_DONE);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));
        when(outcomes.claim(eq("e-1"), anyString())).thenReturn(1, 0);

        assertEquals(1, service.reconcilePublishedGrants());
        assertEquals(0, service.reconcilePublishedGrants());
        verify(member, times(1)).queryBenefitOrderStatus("o-1");
        verify(publisher, times(1)).publishTradeCompleted(any(TradeCompletedEvent.class));
    }

    @Test
    public void refundedGrantedAndRevokedEligibleAreManualWithoutReplay() {
        candidate("e-1", "o-1", OrderStatusVO.WAIT_REFUND);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("GRANTED"));
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("WAIT_REFUND"),
                eq("GRANTED"), eq("INELIGIBLE_ORDER"), isNull());

        when(orders.queryOrderByOrderId("o-1")).thenReturn(order(OrderStatusVO.MARKET));
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("REVOKED"));
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("REVOKED"), eq("REVOKED_ELIGIBLE_ORDER"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void revokeRowOrClosedOrderBlocksPendingReplay() {
        candidate("e-1", "o-1", OrderStatusVO.CLOSE);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("CLOSE"),
                eq("PENDING"), eq("INELIGIBLE_ORDER"), isNull());

        when(orders.queryOrderByOrderId("o-1")).thenReturn(order(OrderStatusVO.MARKET));
        when(events.findByOrderIdAndEventType("o-1", "GROUP_BUY_REVOKED"))
                .thenReturn(new BenefitEvent());
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("PENDING"), eq("REVOKE_EVENT_CONFLICT"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void refundedOrderWithAppliedRevokeIsVerified() {
        candidate("e-1", "o-1", OrderStatusVO.CLOSE);
        BenefitEvent revoke = new BenefitEvent();
        revoke.setEventPublished(true);
        when(events.findByOrderIdAndEventType("o-1", "GROUP_BUY_REVOKED")).thenReturn(revoke);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("REVOKED"));

        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("REVOKED"), eq("CLOSE"),
                eq("REVOKED"), eq("REVOKE_APPLIED"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void refundedOrderWaitsForUnpublishedRevoke() {
        candidate("e-1", "o-1", OrderStatusVO.CLOSE);
        BenefitEvent revoke = new BenefitEvent();
        revoke.setEventPublished(false);
        when(events.findByOrderIdAndEventType("o-1", "GROUP_BUY_REVOKED")).thenReturn(revoke);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));

        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("UNAVAILABLE"), eq("CLOSE"),
                eq("PENDING"), eq("REVOKE_OUTBOX_PENDING"), eq(5));
        verifyNoInteractions(publisher);
    }

    @Test
    public void applicationErrorIsRetriedButInvalidResponseIsManual() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        MemberResult<MemberBenefitStatusDTO> unavailable = status("PENDING");
        unavailable.setCode(503);
        MemberResult<MemberBenefitStatusDTO> invalid = status("PENDING");
        invalid.setCode(403);
        when(member.queryBenefitOrderStatus("o-1"))
                .thenThrow(new IllegalStateException("timeout")).thenReturn(unavailable, invalid);
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("UNAVAILABLE"), eq("MARKET"),
                isNull(), eq("MEMBER_UNAVAILABLE"), eq(5));
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("UNAVAILABLE"), eq("MARKET"),
                isNull(), eq("MEMBER_APPLICATION_UNAVAILABLE"), eq(5));
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                isNull(), eq("INVALID_MEMBER_RESPONSE"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void mismatchedQuotaSnapshotCannotReplay() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        OrderEntity changed = order(OrderStatusVO.MARKET);
        changed.setBaseQuotaSnapshot(61L);
        when(orders.queryOrderByOrderId("o-1")).thenReturn(changed);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));

        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("PENDING"), eq("INVALID_OUTBOX_PAYLOAD"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void productChangedBeforeSendCannotReplay() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        OrderEntity changed = order(OrderStatusVO.MARKET);
        changed.setProductCode("QUOTA_STANDARD");
        when(orders.queryOrderByOrderId("o-1")).thenReturn(order(OrderStatusVO.MARKET), changed);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));

        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("PENDING"), eq("ORDER_PAYLOAD_CHANGED"), isNull());
        verify(outcomes, never()).recordReplayIntent(anyString(), anyString(), anyInt());
        verifyNoInteractions(publisher);
    }

    @Test
    public void grantedQuotaMismatchNeedsManualReview() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        MemberResult<MemberBenefitStatusDTO> memberGrant = status("GRANTED");
        memberGrant.getData().setGrantedQuotaMicro("59000000");
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(memberGrant);

        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("GRANTED"), eq("GRANT_MISMATCH"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void grantedManualReviewFlagCannotBeConfirmed() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        MemberResult<MemberBenefitStatusDTO> memberGrant = status("GRANTED");
        memberGrant.getData().setManualReview(true);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(memberGrant);

        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("GRANTED"), eq("MEMBER_MANUAL_REVIEW"), isNull());
        verifyNoInteractions(publisher);
    }

    @Test
    public void replayLimitAndClaimRaceCannotSend() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));
        when(outcomes.findByEventId("e-1")).thenReturn(reconciliation(3));
        service.reconcilePublishedGrants();
        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("MARKET"),
                eq("PENDING"), eq("REPLAY_LIMIT"), isNull());
        verify(outcomes, never()).recordReplayIntent(anyString(), anyString(), anyInt());
        verifyNoInteractions(publisher);
    }

    @Test
    public void boundedBatchSkipsUnclaimedRowsAndPassesAgeCutoff() {
        List<BenefitEvent> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(BenefitEvent.builder().eventId("e-" + i).orderId("o-" + i).build());
        }
        when(events.queryDuePublishedGrants(any(Date.class), eq(50))).thenReturn(batch);
        when(outcomes.claim(anyString(), anyString())).thenReturn(0);

        assertEquals(0, service.reconcilePublishedGrants());
        verify(outcomes, times(50)).insertIfAbsent(anyString());
        verify(outcomes, times(50)).claim(anyString(), anyString());
        verify(events).queryDuePublishedGrants(any(Date.class), eq(50));
        verifyNoInteractions(orders, member, publisher);
    }

    @Test
    public void orderBecomesRefundedBetweenStatusCheckAndSend() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        when(orders.queryOrderByOrderId("o-1")).thenReturn(
                order(OrderStatusVO.MARKET), order(OrderStatusVO.WAIT_REFUND));
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));

        service.reconcilePublishedGrants();

        verify(outcomes).finish(eq("e-1"), anyString(), eq("MANUAL"), eq("WAIT_REFUND"),
                eq("PENDING"), eq("CHANGED_BEFORE_REPLAY"), isNull());
        verify(outcomes, never()).recordReplayIntent(anyString(), anyString(), anyInt());
        verifyNoInteractions(publisher);
    }

    @Test
    public void publishFailureRecordsIntentBeforeSendAndBacksOff() {
        candidate("e-1", "o-1", OrderStatusVO.MARKET);
        when(member.queryBenefitOrderStatus("o-1")).thenReturn(status("PENDING"));
        when(outcomes.findByEventId("e-1")).thenReturn(reconciliation(1));
        doThrow(new IllegalStateException("broker down"))
                .when(publisher).publishTradeCompleted(any(TradeCompletedEvent.class));

        service.reconcilePublishedGrants();

        InOrder sequence = inOrder(outcomes, publisher);
        sequence.verify(outcomes).recordReplayIntent(eq("e-1"), anyString(), eq(3));
        sequence.verify(publisher).publishTradeCompleted(any(TradeCompletedEvent.class));
        sequence.verify(outcomes).finish(eq("e-1"), anyString(), eq("UNAVAILABLE"), eq("MARKET"),
                eq("PENDING"), eq("PUBLISH_FAILED"), eq(15));
    }

    private void candidate(String eventId, String orderId, OrderStatusVO orderStatus) {
        BenefitEvent event = BenefitEvent.builder().eventId(eventId).eventType("GROUP_BUY_COMPLETED")
                .orderId(orderId).userId(101L).productCode("QUOTA_LIGHT").baseQuota(60L).build();
        when(events.queryDuePublishedGrants(any(Date.class), eq(50)))
                .thenReturn(Collections.singletonList(event));
        when(orders.queryOrderByOrderId(orderId)).thenReturn(order(orderStatus));
    }

    private OrderEntity order(OrderStatusVO status) {
        return OrderEntity.builder().orderStatusVO(status).userId("101")
                .productCode("QUOTA_LIGHT").baseQuotaSnapshot(60L).build();
    }

    private BenefitReconciliation reconciliation(int attempts) {
        BenefitReconciliation row = new BenefitReconciliation();
        row.setReplayAttempts(attempts);
        return row;
    }

    private MemberResult<MemberBenefitStatusDTO> status(String status) {
        MemberResult<MemberBenefitStatusDTO> response = new MemberResult<>();
        response.setCode(200);
        MemberBenefitStatusDTO details = new MemberBenefitStatusDTO();
        details.setStatus(status);
        if ("GRANTED".equals(status)) {
            details.setUserId("101");
            details.setProductCode("QUOTA_LIGHT");
            details.setGrantedQuotaMicro("60000000");
        }
        response.setData(details);
        return response;
    }
}
