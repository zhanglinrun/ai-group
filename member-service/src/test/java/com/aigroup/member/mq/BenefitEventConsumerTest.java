package com.aigroup.member.mq;

import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.service.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BenefitEventConsumerTest {

    private static final String PAYLOAD = "{\"orderId\":\"order-1\",\"userId\":1001}";

    @Test
    void handlesBenefitEventAfterRabbitDelivery() {
        MemberService memberService = mock(MemberService.class);
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        consumer.onTradeCompleted(PAYLOAD);

        verify(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
    }

    @Test
    void doesNotAcknowledgeWhenBenefitHandlingFails() {
        MemberService memberService = mock(MemberService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> consumer.onTradeCompleted(PAYLOAD));
    }
}
