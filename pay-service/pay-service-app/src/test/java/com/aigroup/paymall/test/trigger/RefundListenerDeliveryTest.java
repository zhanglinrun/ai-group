package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.listener.RefundSuccessTopicListener;
import com.aigroup.paymall.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

public class RefundListenerDeliveryTest {
    private static final String PAID = "{\"type\":\"paid_formed\",\"userId\":\"u1\",\"teamId\":\"t1\",\"activityId\":101,\"source\":\"s01\",\"channel\":\"c01\",\"outTradeNo\":\"order-1\"}";
    private RefundSuccessTopicListener listener;
    private IOrderService service;

    @Before
    public void setUp() {
        service = mock(IOrderService.class);
        listener = new RefundSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", service);
    }

    @Test
    public void validRefundAndReplayAreAcknowledged() throws Exception {
        when(service.refundTeamPayOrder("u1", "t1", 101L, "s01", "c01", "order-1")).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.consume(PAID, ack);
        listener.consumeDlt(PAID, ack);
        verify(service, times(2)).refundTeamPayOrder("u1", "t1", 101L, "s01", "c01", "order-1");
        verify(ack, times(2)).acknowledge();
    }

    @Test
    public void failedRefundIsNotAcknowledged() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        Assert.assertThrows(AppException.class, () -> listener.consume(PAID, ack));
        Assert.assertThrows(AppException.class, () -> listener.consumeDlt(PAID, ack));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void legacyPaidMessageCannotCallOldUnscopedRefund() throws Exception {
        Assert.assertThrows(IllegalArgumentException.class, () -> listener.listener(
                "{\"type\":\"paid_unformed\",\"userId\":\"u1\",\"outTradeNo\":\"order-1\"}"));
        verify(service, never()).refundPayOrder(anyString(), anyString());
    }

    @Test
    public void unpaidUnlockIsNotAChargeback() {
        listener.listener("{\"type\":\"unpaid_unlock\"}");
        verifyNoInteractions(service);
    }
}
