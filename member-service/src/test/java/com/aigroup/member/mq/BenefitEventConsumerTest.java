package com.aigroup.member.mq;

import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.service.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BenefitEventConsumerTest {

    private static final String PAYLOAD = "{\"orderId\":\"order-1\",\"userId\":1001}";

    @Test
    void acknowledgesOnlyAfterBenefitHandlingSucceeds() {
        MemberService memberService = mock(MemberService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        consumer.onTradeCompleted(PAYLOAD, acknowledgment);

        verify(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenBenefitHandlingFails() {
        MemberService memberService = mock(MemberService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> consumer.onTradeCompleted(PAYLOAD, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }
}
