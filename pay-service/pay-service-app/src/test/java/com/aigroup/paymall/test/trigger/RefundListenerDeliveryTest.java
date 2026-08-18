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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class RefundListenerDeliveryTest {

    private RefundSuccessTopicListener listener;
    private IOrderService orderService;

    @Before
    public void setUp() {
        orderService = mock(IOrderService.class);
        listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", orderService);
    }

    @Test
    public void listenerPropagatesRefundFailure() throws Exception {
        when(orderService.refundPayOrder("u1", "order-001")).thenReturn(false);

        try {
            listener.listener("{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"outTradeNo\":\"order-001\"}");
            Assert.fail("expected AppException on refund business failure");
        } catch (AppException expected) {
            // Kafka DefaultErrorHandler / DLT owns the retry.
        }
        verify(orderService).refundPayOrder("u1", "order-001");
    }

    @Test
    public void listenerCompletesRefund() throws Exception {
        when(orderService.refundPayOrder("u1", "order-002")).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.consume("{\"type\":\"paid_formed\",\"userId\":\"u1\",\"outTradeNo\":\"order-002\"}", ack);

        verify(orderService).refundPayOrder("u1", "order-002");
        verify(ack).acknowledge();
    }

    @Test
    public void consumeDoesNotAcknowledgeWhenRefundFails() throws Exception {
        when(orderService.refundPayOrder("u1", "order-001")).thenReturn(false);
        Acknowledgment ack = mock(Acknowledgment.class);

        try {
            listener.consume("{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"outTradeNo\":\"order-001\"}", ack);
            Assert.fail("expected AppException on refund business failure");
        } catch (AppException expected) {
            verify(ack, never()).acknowledge();
        }
    }

    @Test
    public void dltReplaysRefundAndAcknowledges() throws Exception {
        when(orderService.refundPayOrder("u1", "order-002")).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.consumeDlt("{\"type\":\"paid_formed\",\"userId\":\"u1\",\"outTradeNo\":\"order-002\"}", ack);

        verify(orderService).refundPayOrder("u1", "order-002");
        verify(ack).acknowledge();
    }

    @Test
    public void dltExhaustedStillAcknowledges() throws Exception {
        when(orderService.refundPayOrder("u1", "order-001")).thenReturn(false);
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.consumeDlt("{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"outTradeNo\":\"order-001\"}", ack);

        verify(ack).acknowledge();
    }

    @Test
    public void listenerIgnoresUnpaidUnlock() throws Exception {
        listener.listener("{\"type\":\"unpaid_unlock\",\"userId\":\"u1\",\"outTradeNo\":\"order-003\"}");
        verifyNoInteractions(orderService);
    }
}
