package com.aigroup.member.mq;

import com.aigroup.member.dto.UserRegisteredEvent;
import com.aigroup.member.service.MemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** Creates the initial quota account without coupling Auth to Member's database. */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegisteredEventConsumer {

    private final MemberService memberService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${ai-group.kafka.topics.user-registered:auth.user_registered}",
            groupId = "member-service")
    public void consumeUserRegistered(String message, Acknowledgment ack) {
        onUserRegistered(message);
        ack.acknowledge();
    }

    public void onUserRegistered(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            JsonNode payload = envelope.has("payload") ? envelope.get("payload") : envelope;
            UserRegisteredEvent event = objectMapper.treeToValue(payload, UserRegisteredEvent.class);
            event.setEventId(envelope.path("eventId").asText(event.getEventId()));
            event.setEventType(envelope.path("eventType").asText(event.getEventType()));
            if (event.getUserId() == null || event.getUserId() <= 0) {
                throw new IllegalArgumentException("UserRegistered event does not contain a valid userId");
            }
            memberService.initFree(event.getUserId());
            log.info("Initialized quota account from UserRegistered eventId={} userId={}",
                    event.getEventId(), event.getUserId());
        } catch (Exception ex) {
            log.error("Failed to consume UserRegistered event", ex);
            throw new IllegalStateException("UserRegistered event consume failed", ex);
        }
    }
}
