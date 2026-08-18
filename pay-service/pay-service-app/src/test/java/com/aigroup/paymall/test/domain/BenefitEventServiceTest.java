package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.benefit.adapter.repository.IBenefitEventRepository;
import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.domain.benefit.service.BenefitEventService;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.types.enums.OutboxEventType;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class BenefitEventServiceTest {

    private BenefitEventService benefitEventService;
    private IOrderRepository orderRepository;
    private IBenefitEventRepository benefitEventRepository;
    private IBenefitEventPort benefitEventPort;

    @Before
    public void setUp() {
        orderRepository = mock(IOrderRepository.class);
        benefitEventRepository = mock(IBenefitEventRepository.class);
        benefitEventPort = mock(IBenefitEventPort.class);
        benefitEventService = new BenefitEventService(orderRepository, benefitEventRepository, benefitEventPort);
    }

    @Test
    public void enqueueCompletedOrderEvents_writesTwoOutboxRowsWithoutPublishing() {
        OrderEntity order = order("order-001", MarketTypeVO.GROUP_BUY_MARKET, 60L);
        when(orderRepository.queryOrderByOrderId("order-001")).thenReturn(order);

        benefitEventService.enqueueCompletedOrderEvents(Collections.singletonList("order-001"));

        ArgumentCaptor<BenefitEventEntity> eventCaptor = ArgumentCaptor.forClass(BenefitEventEntity.class);
        verify(benefitEventRepository, times(2)).insert(eventCaptor.capture());
        List<BenefitEventEntity> events = eventCaptor.getAllValues();
        assertEquals(OutboxEventType.ORDER_PAY_SUCCESS.name(), events.get(0).getEventType());
        assertEquals(OutboxEventType.GROUP_BUY_COMPLETED.name(), events.get(1).getEventType());
        assertEquals(Long.valueOf(60L), events.get(1).getBaseQuota());
        assertFalse(events.get(0).getEventPublished());
        verifyNoInteractions(benefitEventPort);
        verify(benefitEventRepository, never()).markPublished(any(String.class));
    }

    @Test
    public void enqueueMethodsAreTransactionalButPublisherRunsOutsideBusinessTransaction() throws Exception {
        assertNotNull(BenefitEventService.class
                .getMethod("enqueueCompletedOrderEvents", List.class)
                .getAnnotation(Transactional.class));
        assertNotNull(BenefitEventService.class
                .getMethod("enqueueRevokedBenefitEvents", List.class)
                .getAnnotation(Transactional.class));
        Transactional publisherTransaction = BenefitEventService.class
                .getMethod("publishPendingEvents")
                .getAnnotation(Transactional.class);
        assertNotNull(publisherTransaction);
        assertEquals(Propagation.NOT_SUPPORTED, publisherTransaction.propagation());
    }

    @Test
    public void publishPendingEvents_publishesBenefitThenMarksRow() {
        BenefitEventEntity pending = pending(
                "evt-benefit", "order-002", OutboxEventType.GROUP_BUY_COMPLETED, 60L);
        when(benefitEventRepository.queryUnpublished(100)).thenReturn(Collections.singletonList(pending));

        int count = benefitEventService.publishPendingEvents();

        assertEquals(1, count);
        ArgumentCaptor<TradeCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TradeCompletedEvent.class);
        verify(benefitEventPort).publishTradeCompleted(eventCaptor.capture());
        assertEquals("evt-benefit", eventCaptor.getValue().getEventId());
        assertEquals(Long.valueOf(60L), eventCaptor.getValue().getBaseQuota());
        verify(benefitEventRepository).markPublished("evt-benefit");
    }

    @Test
    public void publishPendingEvents_publishesOrderFulfillmentThenMarksRow() {
        BenefitEventEntity pending = pending(
                "evt-fulfillment", "order-003", OutboxEventType.ORDER_PAY_SUCCESS, 60L);
        when(benefitEventRepository.queryUnpublished(100)).thenReturn(Collections.singletonList(pending));

        int count = benefitEventService.publishPendingEvents();

        assertEquals(1, count);
        verify(benefitEventPort).publishOrderPaySuccess("evt-fulfillment", 10001L, "order-003");
        verify(benefitEventPort, never()).publishTradeCompleted(any(TradeCompletedEvent.class));
        verify(benefitEventRepository).markPublished("evt-fulfillment");
    }

    @Test
    public void publishPendingEvents_keepsOutboxPendingWhenBrokerConfirmationFails() {
        BenefitEventEntity pending = pending(
                "evt-ack-timeout", "order-004", OutboxEventType.GROUP_BUY_COMPLETED, 60L);
        when(benefitEventRepository.queryUnpublished(100)).thenReturn(Collections.singletonList(pending));
        doThrow(new IllegalStateException("kafka publish timed out"))
                .when(benefitEventPort).publishTradeCompleted(any(TradeCompletedEvent.class));

        assertThrows(IllegalStateException.class, () -> benefitEventService.publishPendingEvents());
        verify(benefitEventRepository, never()).markPublished("evt-ack-timeout");
    }

    @Test
    public void publishPendingEvents_keepsFulfillmentPendingWhenPublisherFails() {
        BenefitEventEntity pending = pending(
                "evt-fulfillment-timeout", "order-fulfillment-timeout",
                OutboxEventType.ORDER_PAY_SUCCESS, 60L);
        when(benefitEventRepository.queryUnpublished(100)).thenReturn(Collections.singletonList(pending));
        doThrow(new IllegalStateException("kafka publish timed out"))
                .when(benefitEventPort).publishOrderPaySuccess(
                        "evt-fulfillment-timeout", 10001L, "order-fulfillment-timeout");

        assertThrows(IllegalStateException.class, () -> benefitEventService.publishPendingEvents());
        verify(benefitEventRepository, never()).markPublished("evt-fulfillment-timeout");
    }

    @Test
    public void publishPendingEvents_publishesRevokeTombstoneWithoutCompletedBenefit() {
        BenefitEventEntity revoke = pending(
                "evt-revoke", "order-005", OutboxEventType.GROUP_BUY_REVOKED, 60L);
        when(benefitEventRepository.queryUnpublished(100)).thenReturn(Collections.singletonList(revoke));

        int count = benefitEventService.publishPendingEvents();

        assertEquals(1, count);
        ArgumentCaptor<TradeCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TradeCompletedEvent.class);
        verify(benefitEventPort).publishTradeCompleted(eventCaptor.capture());
        assertEquals(OutboxEventType.GROUP_BUY_REVOKED.name(), eventCaptor.getValue().getEventType());
        verify(benefitEventRepository).markPublished("evt-revoke");
    }

    @Test
    public void publishPendingEvents_defersGrantWhenRevokeTombstoneIsStillPending() {
        BenefitEventEntity completed = pending(
                "evt-completed-deferred", "order-revoking",
                OutboxEventType.GROUP_BUY_COMPLETED, 60L);
        BenefitEventEntity revoke = pending(
                "evt-revoke-pending", "order-revoking",
                OutboxEventType.GROUP_BUY_REVOKED, 60L);
        when(benefitEventRepository.queryUnpublished(100)).thenReturn(Collections.singletonList(completed));
        when(benefitEventRepository.findByOrderIdAndEventType(
                "order-revoking", OutboxEventType.GROUP_BUY_REVOKED.name())).thenReturn(revoke);

        int count = benefitEventService.publishPendingEvents();

        assertEquals(0, count);
        verifyNoInteractions(benefitEventPort);
        verify(benefitEventRepository, never()).markPublished("evt-completed-deferred");
    }

    @Test
    public void enqueueCompletedOrderEvents_propagatesMissingOutboxInsertFailure() {
        when(orderRepository.queryOrderByOrderId("order-insert-failure"))
                .thenReturn(order("order-insert-failure", MarketTypeVO.GROUP_BUY_MARKET, 60L));
        doThrow(new IllegalStateException("database unavailable"))
                .when(benefitEventRepository).insert(any(BenefitEventEntity.class));

        assertThrows(IllegalStateException.class, () -> benefitEventService
                .enqueueCompletedOrderEvents(Collections.singletonList("order-insert-failure")));

        verifyNoInteractions(benefitEventPort);
    }

    @Test
    public void enqueueRevokedBenefitEvents_propagatesMissingOutboxInsertFailure() {
        when(orderRepository.queryOrderByOrderId("order-revoke-insert-failure"))
                .thenReturn(order("order-revoke-insert-failure", MarketTypeVO.GROUP_BUY_MARKET, 60L));
        doThrow(new IllegalStateException("database unavailable"))
                .when(benefitEventRepository).insert(any(BenefitEventEntity.class));

        assertThrows(IllegalStateException.class, () -> benefitEventService
                .enqueueRevokedBenefitEvents(Collections.singletonList("order-revoke-insert-failure")));
    }

    private OrderEntity order(String orderId, MarketTypeVO marketType, Long baseQuota) {
        return OrderEntity.builder()
                .userId("10001")
                .orderId(orderId)
                .productId("9890002")
                .productCode("QUOTA_LIGHT")
                .baseQuotaSnapshot(baseQuota)
                .marketType(marketType.getCode())
                .build();
    }

    private BenefitEventEntity pending(String eventId, String orderId, OutboxEventType type,
                                       Long baseQuota) {
        return BenefitEventEntity.builder()
                .eventId(eventId)
                .eventType(type.name())
                .userId(10001L)
                .orderId(orderId)
                .productCode("QUOTA_LIGHT")
                .baseQuota(baseQuota)
                .eventPublished(false)
                .build();
    }
}
