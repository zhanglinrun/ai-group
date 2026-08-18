package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.trigger.listener.RefundSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class KafkaListenerDeliveryTest {

    private static final String REFUND = "{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"teamId\":\"t1\",\"activityId\":1,\"orderId\":\"o1\",\"outTradeNo\":\"trade-1\"}";

    @Test
    public void refundCompletesOnlyAfterStockRestore() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        RefundSuccessTopicListener listener = listener(service);

        listener.consume(REFUND, ack);

        verify(service).restoreTeamLockStock(any());
        verify(ack).acknowledge();
    }

    @Test
    public void refundFailureDoesNotAcknowledge() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        doThrow(new IllegalStateException("db unavailable")).when(service).restoreTeamLockStock(any());
        Acknowledgment ack = mock(Acknowledgment.class);
        RefundSuccessTopicListener listener = listener(service);

        try {
            listener.consume(REFUND, ack);
            Assert.fail("business failure must be retried");
        } catch (RuntimeException expected) {
            Assert.assertNotNull(expected.getCause());
            verify(ack, never()).acknowledge();
        }
    }

    @Test
    public void dltReplaysStockRestoreAndAcknowledges() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        RefundSuccessTopicListener listener = listener(service);

        listener.consumeDlt(REFUND, ack);

        verify(service).restoreTeamLockStock(any());
        verify(ack).acknowledge();
    }

    @Test
    public void dltExhaustedStillAcknowledges() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        doThrow(new IllegalStateException("db unavailable")).when(service).restoreTeamLockStock(any());
        Acknowledgment ack = mock(Acknowledgment.class);
        RefundSuccessTopicListener listener = listener(service);

        listener.consumeDlt(REFUND, ack);

        verify(ack).acknowledge();
    }

    private RefundSuccessTopicListener listener(ITradeRefundOrderService service) {
        RefundSuccessTopicListener listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "tradeRefundOrderService", service);
        return listener;
    }
}
