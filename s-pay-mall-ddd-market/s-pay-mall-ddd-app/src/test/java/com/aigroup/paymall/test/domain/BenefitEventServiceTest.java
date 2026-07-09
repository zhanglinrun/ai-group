package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.benefit.adapter.repository.IBenefitEventRepository;
import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.domain.benefit.service.BenefitEventService;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.types.enums.BenefitEventType;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    public void publishGroupBuyCompletedEvents_publishesOnGroupSettlement() {
        OrderEntity order = OrderEntity.builder()
                .userId("10001")
                .orderId("order-001")
                .productId("9890001")
                .productCode("PRO_MONTH")
                .marketType(MarketTypeVO.GROUP_BUY_MARKET.getCode())
                .build();
        when(orderRepository.queryOrderByOrderId("order-001")).thenReturn(order);
        when(benefitEventRepository.findByOrderIdAndEventType("order-001", BenefitEventType.GROUP_BUY_COMPLETED.name()))
                .thenReturn(null);

        benefitEventService.publishGroupBuyCompletedEvents(Collections.singletonList("order-001"));

        ArgumentCaptor<TradeCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TradeCompletedEvent.class);
        verify(benefitEventPort).publishTradeCompleted(eventCaptor.capture());
        TradeCompletedEvent event = eventCaptor.getValue();
        assertEquals(Long.valueOf(10001L), event.getUserId());
        assertEquals("order-001", event.getOrderId());
        assertEquals("PRO_MONTH", event.getProductCode());
        assertEquals(BenefitEventType.GROUP_BUY_COMPLETED.name(), event.getEventType());
        verify(benefitEventRepository).markPublished(any(String.class));
    }

    @Test
    public void publishGroupBuyCompletedEvents_skipsNonGroupOrder() {
        OrderEntity order = OrderEntity.builder()
                .userId("10001")
                .orderId("order-002")
                .marketType(MarketTypeVO.NO_MARKET.getCode())
                .build();
        when(orderRepository.queryOrderByOrderId("order-002")).thenReturn(order);

        benefitEventService.publishGroupBuyCompletedEvents(Collections.singletonList("order-002"));

        verifyNoInteractions(benefitEventPort);
        verify(benefitEventRepository, never()).insert(any(BenefitEventEntity.class));
    }

    @Test
    public void republishPendingEvents_retriesUnpublished() {
        BenefitEventEntity pending = BenefitEventEntity.builder()
                .eventId("evt-1")
                .eventType(BenefitEventType.GROUP_BUY_COMPLETED.name())
                .userId(10001L)
                .orderId("order-003")
                .productCode("PRO_MONTH")
                .eventPublished(false)
                .build();
        when(benefitEventRepository.queryUnpublished(BenefitEventType.GROUP_BUY_COMPLETED.name(), 50))
                .thenReturn(Collections.singletonList(pending));

        int count = benefitEventService.republishPendingEvents();

        assertEquals(1, count);
        verify(benefitEventPort).publishTradeCompleted(any(TradeCompletedEvent.class));
        verify(benefitEventRepository).markPublished("evt-1");
    }

}
