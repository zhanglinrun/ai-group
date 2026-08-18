package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.listener.TeamSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class KafkaListenerDeliveryTest {

    private static final String TEAM_SUCCESS = "{\"teamId\":\"t1\",\"outTradeNoList\":[\"trade-1\"]}";

    @Test
    public void teamSuccessCompletesSettlementAndAcknowledges() {
        IOrderService service = mock(IOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        TeamSuccessTopicListener listener = listener(service);

        listener.consume(TEAM_SUCCESS, ack);

        verify(service).changeOrderMarketSettlement(List.of("trade-1"));
        verify(ack).acknowledge();
    }

    @Test
    public void teamSuccessFailureDoesNotAcknowledge() {
        IOrderService service = mock(IOrderService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(service).changeOrderMarketSettlement(anyList());
        Acknowledgment ack = mock(Acknowledgment.class);
        TeamSuccessTopicListener listener = listener(service);

        assertFails(() -> listener.consume(TEAM_SUCCESS, ack));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void teamSuccessDltReplaysSettlementAndAcknowledges() {
        IOrderService service = mock(IOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        TeamSuccessTopicListener listener = listener(service);

        listener.consumeDlt(TEAM_SUCCESS, ack);

        verify(service).changeOrderMarketSettlement(List.of("trade-1"));
        verify(ack).acknowledge();
    }

    @Test
    public void teamSuccessDltExhaustedStillAcknowledges() {
        IOrderService service = mock(IOrderService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(service).changeOrderMarketSettlement(anyList());
        Acknowledgment ack = mock(Acknowledgment.class);
        TeamSuccessTopicListener listener = listener(service);

        listener.consumeDlt(TEAM_SUCCESS, ack);

        verify(ack).acknowledge();
    }

    private TeamSuccessTopicListener listener(IOrderService service) {
        TeamSuccessTopicListener listener = new TeamSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", service);
        return listener;
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
