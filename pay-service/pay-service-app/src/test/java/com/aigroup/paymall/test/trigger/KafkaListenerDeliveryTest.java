package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.order.model.entity.TeamSettlementMember;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.listener.TeamSuccessTopicListener;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class KafkaListenerDeliveryTest {
    private static final String TEAM_SUCCESS = "{\"teamId\":\"t1\",\"activityId\":101,\"members\":[{\"userId\":\"u1\",\"source\":\"s01\",\"channel\":\"c01\",\"outTradeNo\":\"trade-1\"}]}";

    @Test
    public void teamSuccessCompletesSettlementAndAcknowledges() {
        IOrderService service = mock(IOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        listener(service).consume(TEAM_SUCCESS, ack);
        verify(service).changeOrderMarketSettlement("t1", 101L,
                List.of(new TeamSettlementMember("u1", "s01", "c01", "trade-1")));
        verify(ack).acknowledge();
    }

    @Test
    public void legacyOrIncompleteTeamIdentityIsRejectedWithoutSettlement() {
        IOrderService service = mock(IOrderService.class);
        TeamSuccessTopicListener listener = listener(service);
        for (String payload : new String[]{
                "{\"teamId\":\"t1\",\"outTradeNoList\":[\"trade-1\"]}",
                "{\"teamId\":\"t1\",\"activityId\":101,\"members\":[{\"userId\":\"\",\"source\":\"s01\",\"channel\":\"c01\",\"outTradeNo\":\"trade-1\"}]}"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> listener.listener(payload));
        }
        verifyNoInteractions(service);
    }

    @Test
    public void failureAndDltDoNotAcknowledge() {
        IOrderService service = mock(IOrderService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(service).changeOrderMarketSettlement(eq("t1"), eq(101L), anyList());
        Acknowledgment ack = mock(Acknowledgment.class);
        TeamSuccessTopicListener listener = listener(service);
        Assert.assertThrows(IllegalStateException.class, () -> listener.consume(TEAM_SUCCESS, ack));
        Assert.assertThrows(IllegalStateException.class, () -> listener.consumeDlt(TEAM_SUCCESS, ack));
        verify(ack, never()).acknowledge();
    }

    private TeamSuccessTopicListener listener(IOrderService service) {
        TeamSuccessTopicListener listener = new TeamSuccessTopicListener();
        ReflectionTestUtils.setField(listener, "orderService", service);
        return listener;
    }
}
