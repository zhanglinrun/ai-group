package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.goods.service.IGoodsService;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.listener.OrderPaySuccessListener;
import com.aigroup.paymall.trigger.listener.TeamSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class KafkaListenerDeliveryTest {

    @Test
    public void teamSuccessCompletesSettlement() {
        IOrderService service = mock(IOrderService.class);
        TeamSuccessTopicListener listener = new TeamSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", service);

        listener.listener("{\"teamId\":\"t1\",\"outTradeNoList\":[\"trade-1\"]}");

        verify(service).changeOrderMarketSettlement(List.of("trade-1"));
    }

    @Test
    public void teamSuccessFailureIsPropagated() {
        IOrderService service = mock(IOrderService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(service).changeOrderMarketSettlement(anyList());
        TeamSuccessTopicListener listener = new TeamSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", service);

        assertFails(() -> listener.listener("{\"outTradeNoList\":[\"trade-1\"]}"));
    }

    @Test
    public void orderPaySuccessCompletesDelivery() {
        IGoodsService service = mock(IGoodsService.class);
        OrderPaySuccessListener listener = new OrderPaySuccessListener();
        ReflectionTestUtils.setField(listener, "goodsService", service);

        listener.listener("{\"userId\":\"u1\",\"tradeNo\":\"trade-1\"}");

        verify(service).changeOrderDealDone("trade-1");
    }

    private void assertFails(Runnable action) {
        try {
            action.run();
            Assert.fail("business failure must be retried");
        } catch (Exception expected) {
            Assert.assertTrue(expected instanceof RuntimeException);
        }
    }
}
