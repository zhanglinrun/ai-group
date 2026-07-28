package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.listener.RefundSuccessTopicListener;
import com.aigroup.paymall.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C3: a business refund failure (refundPayOrder returns false) must throw so
 * the message is retried/dead-lettered instead of being acked and lost.
 */
public class RefundSuccessTopicListenerTest {

    private RefundSuccessTopicListener listener;
    private IOrderService orderService;
    private Acknowledgment acknowledgment;

    @Before
    public void setUp() {
        orderService = mock(IOrderService.class);
        acknowledgment = mock(Acknowledgment.class);
        listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", orderService);
    }

    @Test
    public void listener_throwsWhenRefundPayOrderReturnsFalse() throws Exception {
        when(orderService.refundPayOrder("u1", "order-001")).thenReturn(false);

        String message = "{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"outTradeNo\":\"order-001\"}";
        try {
            listener.listener(message, acknowledgment);
            Assert.fail("expected AppException on refund business failure");
        } catch (AppException expected) {
            // triggers MQ redelivery, then DLQ after retry exhaustion (C5)
        }
        verify(orderService).refundPayOrder("u1", "order-001");
        verifyNoInteractions(acknowledgment);
    }

    @Test
    public void listener_acksWhenRefundPayOrderSucceeds() throws Exception {
        when(orderService.refundPayOrder("u1", "order-002")).thenReturn(true);

        String message = "{\"type\":\"paid_formed\",\"userId\":\"u1\",\"outTradeNo\":\"order-002\"}";
        listener.listener(message, acknowledgment);

        verify(orderService).refundPayOrder("u1", "order-002");
        verify(acknowledgment).acknowledge();
    }

    @Test
    public void listener_ignoresUnpaidRefundTypes() throws Exception {
        String message = "{\"type\":\"unpaid_unlock\",\"userId\":\"u1\",\"outTradeNo\":\"order-003\"}";
        listener.listener(message, acknowledgment);

        verifyNoInteractions(orderService);
        verify(acknowledgment).acknowledge();
    }

}
