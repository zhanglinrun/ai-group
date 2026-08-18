package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.trigger.listener.RefundSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class KafkaListenerDeliveryTest {

    private static final String REFUND = "{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"teamId\":\"t1\",\"activityId\":1,\"orderId\":\"o1\",\"outTradeNo\":\"trade-1\"}";

    @Test
    public void refundCompletesOnlyAfterStockRestore() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        RefundSuccessTopicListener listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "tradeRefundOrderService", service);

        listener.listener(REFUND);

        verify(service).restoreTeamLockStock(any());
    }

    @Test
    public void refundFailureIsPropagatedForRedelivery() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        doThrow(new IllegalStateException("db unavailable")).when(service).restoreTeamLockStock(any());
        RefundSuccessTopicListener listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "tradeRefundOrderService", service);

        try {
            listener.listener(REFUND);
            Assert.fail("business failure must be retried");
        } catch (RuntimeException expected) {
            Assert.assertNotNull(expected.getCause());
        }
    }
}
