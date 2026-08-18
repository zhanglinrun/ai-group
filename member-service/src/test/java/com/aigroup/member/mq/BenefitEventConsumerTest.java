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
    void handlesBenefitEventAfterKafkaDelivery() {
        MemberService memberService = mock(MemberService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        consumer.consumeTradeCompleted(PAYLOAD, ack);

        verify(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        verify(ack).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenBenefitHandlingFails() {
        MemberService memberService = mock(MemberService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        Acknowledgment ack = mock(Acknowledgment.class);
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> consumer.consumeTradeCompleted(PAYLOAD, ack));
        verify(ack, never()).acknowledge();
    }

    @Test
    void dltReplaysBenefitEventAndAcknowledges() {
        MemberService memberService = mock(MemberService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        consumer.consumeTradeCompletedDlt(PAYLOAD, ack);

        verify(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        verify(ack).acknowledge();
    }

    @Test
    void dltExhaustedStillAcknowledges() {
        MemberService memberService = mock(MemberService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(memberService).handleBenefitEvent(any(TradeCompletedEvent.class));
        Acknowledgment ack = mock(Acknowledgment.class);
        BenefitEventConsumer consumer = new BenefitEventConsumer(memberService, new ObjectMapper());

        consumer.consumeTradeCompletedDlt(PAYLOAD, ack);

        verify(ack).acknowledge();
    }
}
