package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.goods.service.IGoodsService;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.listener.OrderPaySuccessListener;
import com.aigroup.paymall.trigger.listener.TeamSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class KafkaListenerAcknowledgmentTest {

    @Test
    public void teamSuccessAcknowledgesOnlyAfterSettlementSucceeds() {
        IOrderService service = mock(IOrderService.class);
        TeamSuccessTopicListener listener = teamListener(service);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.listener("{\"teamId\":\"t1\",\"outTradeNoList\":[\"trade-1\"],\"bonusQuota\":10}", acknowledgment);

        verify(service).changeOrderMarketSettlement(List.of("trade-1"), 10);
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void teamSuccessDoesNotAcknowledgeWhenSettlementFails() {
        IOrderService service = mock(IOrderService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(service).changeOrderMarketSettlement(anyList(), anyInt());
        TeamSuccessTopicListener listener = teamListener(service);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        assertFails(() -> listener.listener(
                "{\"outTradeNoList\":[\"trade-1\"],\"bonusQuota\":10}", acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    public void orderPaySuccessAcknowledgesOnlyAfterDeliverySucceeds() {
        IGoodsService service = mock(IGoodsService.class);
        OrderPaySuccessListener listener = orderListener(service);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.listener("{\"userId\":\"u1\",\"tradeNo\":\"trade-1\"}", acknowledgment);

        verify(service).changeOrderDealDone("trade-1");
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void orderPaySuccessDoesNotAcknowledgeWhenDeliveryFails() {
        IGoodsService service = mock(IGoodsService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(service).changeOrderDealDone(any());
        OrderPaySuccessListener listener = orderListener(service);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        assertFails(() -> listener.listener(
                "{\"userId\":\"u1\",\"tradeNo\":\"trade-1\"}", acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    private TeamSuccessTopicListener teamListener(IOrderService service) {
        TeamSuccessTopicListener listener = new TeamSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", service);
        return listener;
    }

    private OrderPaySuccessListener orderListener(IGoodsService service) {
        OrderPaySuccessListener listener = new OrderPaySuccessListener();
        ReflectionTestUtils.setField(listener, "goodsService", service);
        return listener;
    }

    private void assertFails(ThrowingAction action) {
        try {
            action.run();
            Assert.fail("business failure must be retried");
        } catch (Exception expected) {
            Assert.assertTrue(expected instanceof RuntimeException);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
