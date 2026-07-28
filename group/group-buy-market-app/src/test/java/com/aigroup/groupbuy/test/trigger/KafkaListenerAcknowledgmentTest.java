package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.trigger.listener.RefundSuccessTopicListener;
import com.aigroup.groupbuy.trigger.listener.TeamSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class KafkaListenerAcknowledgmentTest {

    private static final String REFUND = "{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"teamId\":\"t1\",\"activityId\":1,\"orderId\":\"o1\",\"outTradeNo\":\"trade-1\"}";

    @Test
    public void teamSuccessAcknowledgesReceivedMessage() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        new TeamSuccessTopicListener().listener("{}", acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    public void refundAcknowledgesOnlyAfterStockRestoreSucceeds() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        RefundSuccessTopicListener listener = listener(service);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.listener(REFUND, acknowledgment);

        verify(service).restoreTeamLockStock(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void refundDoesNotAcknowledgeWhenStockRestoreFails() throws Exception {
        ITradeRefundOrderService service = mock(ITradeRefundOrderService.class);
        doThrow(new IllegalStateException("db unavailable")).when(service).restoreTeamLockStock(any());
        RefundSuccessTopicListener listener = listener(service);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        try {
            listener.listener(REFUND, acknowledgment);
            Assert.fail("business failure must be retried");
        } catch (RuntimeException expected) {
            Assert.assertNotNull(expected.getCause());
        }

        verify(acknowledgment, never()).acknowledge();
    }

    private RefundSuccessTopicListener listener(ITradeRefundOrderService service) {
        RefundSuccessTopicListener listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "tradeRefundOrderService", service);
        return listener;
    }
}
