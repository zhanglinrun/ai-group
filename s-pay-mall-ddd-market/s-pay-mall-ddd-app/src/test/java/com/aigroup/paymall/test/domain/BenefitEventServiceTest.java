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
                .productCode("QUOTA_LIGHT")
                .baseQuotaSnapshot(60L)
                .marketType(MarketTypeVO.GROUP_BUY_MARKET.getCode())
                .build();
        when(orderRepository.queryOrderByOrderId("order-001")).thenReturn(order);
        when(benefitEventRepository.findByOrderIdAndEventType("order-001", BenefitEventType.GROUP_BUY_COMPLETED.name()))
                .thenReturn(null);

        benefitEventService.publishGroupBuyCompletedEvents(Collections.singletonList("order-001"), 18L);

        ArgumentCaptor<TradeCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TradeCompletedEvent.class);
        verify(benefitEventPort).publishTradeCompleted(eventCaptor.capture());
        TradeCompletedEvent event = eventCaptor.getValue();
        assertEquals(Long.valueOf(10001L), event.getUserId());
        assertEquals("order-001", event.getOrderId());
        assertEquals("QUOTA_LIGHT", event.getProductCode());
        assertEquals(Long.valueOf(60L), event.getBaseQuota());
        assertEquals(Long.valueOf(18L), event.getBonusQuota());
        assertEquals(BenefitEventType.GROUP_BUY_COMPLETED.name(), event.getEventType());
        verify(benefitEventRepository).markPublished(any(String.class));
    }

    @Test
    public void publishGroupBuyCompletedEvents_publishesForDirectPurchase() {
        // 直购单（NO_MARKET）支付成功同样发放权益：
        // 旧行为是直接跳过，导致「直接购买」永远不开通会员（详见 OrderService.changeOrderPaySuccess 直购分支）。
        OrderEntity order = OrderEntity.builder()
                .userId("10001")
                .orderId("order-002")
                .productId("9890002")
                .productCode("QUOTA_LIGHT")
                .baseQuotaSnapshot(60L)
                .marketType(MarketTypeVO.NO_MARKET.getCode())
                .build();
        when(orderRepository.queryOrderByOrderId("order-002")).thenReturn(order);
        when(benefitEventRepository.findByOrderIdAndEventType("order-002", BenefitEventType.GROUP_BUY_COMPLETED.name()))
                .thenReturn(null);

        benefitEventService.publishGroupBuyCompletedEvents(Collections.singletonList("order-002"), null);

        ArgumentCaptor<TradeCompletedEvent> eventCaptor = ArgumentCaptor.forClass(TradeCompletedEvent.class);
        verify(benefitEventPort).publishTradeCompleted(eventCaptor.capture());
        assertEquals("QUOTA_LIGHT", eventCaptor.getValue().getProductCode());
        assertEquals(Long.valueOf(60L), eventCaptor.getValue().getBaseQuota());
        verify(benefitEventRepository).insert(any(BenefitEventEntity.class));
    }

    @Test
    public void republishPendingEvents_retriesUnpublished() {
        BenefitEventEntity pending = BenefitEventEntity.builder()
                .eventId("evt-1")
                .eventType(BenefitEventType.GROUP_BUY_COMPLETED.name())
                .userId(10001L)
                .orderId("order-003")
                .productCode("QUOTA_LIGHT")
                .baseQuota(60L)
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
