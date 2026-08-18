package com.aigroup.member.mq;

import com.aigroup.member.service.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserRegisteredEventConsumerTest {

    private static final String PAYLOAD = "{\"eventId\":\"evt-1\",\"eventType\":\"USER_REGISTERED\",\"payload\":{\"userId\":1001}}";

    @Test
    void initializesFreeQuotaAfterKafkaDelivery() {
        MemberService memberService = mock(MemberService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(memberService, new ObjectMapper());

        consumer.consumeUserRegistered(PAYLOAD, ack);

        verify(memberService).initFree(1001L);
        verify(ack).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenInitFails() {
        MemberService memberService = mock(MemberService.class);
        doThrow(new IllegalStateException("db unavailable")).when(memberService).initFree(1001L);
        Acknowledgment ack = mock(Acknowledgment.class);
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(memberService, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> consumer.consumeUserRegistered(PAYLOAD, ack));
        verify(ack, never()).acknowledge();
    }

    @Test
    void dltReplaysInitFreeAndAcknowledges() {
        MemberService memberService = mock(MemberService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(memberService, new ObjectMapper());

        consumer.consumeUserRegisteredDlt(PAYLOAD, ack);

        verify(memberService).initFree(1001L);
        verify(ack).acknowledge();
    }

    @Test
    void dltExhaustedStillAcknowledges() {
        MemberService memberService = mock(MemberService.class);
        doThrow(new IllegalStateException("db unavailable")).when(memberService).initFree(1001L);
        Acknowledgment ack = mock(Acknowledgment.class);
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(memberService, new ObjectMapper());

        consumer.consumeUserRegisteredDlt(PAYLOAD, ack);

        verify(ack).acknowledge();
    }
}
