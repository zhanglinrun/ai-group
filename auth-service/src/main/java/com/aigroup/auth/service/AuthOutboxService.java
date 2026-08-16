package com.aigroup.auth.service;

import com.aigroup.auth.entity.AuthOutboxEvent;
import com.aigroup.auth.entity.User;
import com.aigroup.auth.mapper.AuthOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Auth's transactional-outbox boundary for cross-service identity events. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthOutboxService {

    private static final String USER_REGISTERED = "UserRegistered";
    private static final String USER_REGISTERED_ROUTING_KEY = "auth.user_registered";

    private final AuthOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${spring.rabbitmq.event-exchange:xiongdoctor.events}")
    private String eventExchange;

    @Value("${spring.rabbitmq.confirm-timeout-ms:5000}")
    private long confirmTimeoutMillis;

    @Transactional
    public void enqueueUserRegistered(User user) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("eventId", eventId);
            envelope.put("eventType", USER_REGISTERED);
            envelope.put("schemaVersion", 1);
            envelope.put("aggregateId", String.valueOf(user.getId()));
            envelope.put("traceId", eventId);
            envelope.put("occurredAt", occurredAt.toString());
            ObjectNode payload = envelope.putObject("payload");
            payload.put("userId", user.getId());
            payload.put("username", user.getUsername());
            payload.put("role", user.getRole());

            AuthOutboxEvent event = new AuthOutboxEvent();
            event.setEventId(eventId);
            event.setEventType(USER_REGISTERED);
            event.setRoutingKey(USER_REGISTERED_ROUTING_KEY);
            event.setAggregateId(String.valueOf(user.getId()));
            event.setTraceId(eventId);
            event.setPayload(objectMapper.writeValueAsString(envelope));
            event.setStatus("PENDING");
            event.setAttempts(0);
            event.setOccurredAt(occurredAt);
            outboxMapper.insert(event);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot persist UserRegistered outbox event", ex);
        }
    }

    /** Claim-and-publish loop. Invoked by XXL-JOB or the local scheduler fallback. */
    public void dispatchPending() {
        List<AuthOutboxEvent> pending = outboxMapper.selectPending();
        for (AuthOutboxEvent event : pending) {
            if (outboxMapper.claim(event.getId()) != 1) {
                continue;
            }
            try {
                publishWithConfirm(event);
                outboxMapper.markSent(event.getId());
            } catch (Exception ex) {
                log.error("auth outbox publish failed eventId={} attempt={}",
                        event.getEventId(), event.getAttempts(), ex);
                outboxMapper.markFailed(event.getId(), abbreviate(ex.getMessage()));
            }
        }
    }

    private void publishWithConfirm(AuthOutboxEvent event) throws Exception {
        CorrelationData correlation = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(eventExchange, event.getRoutingKey(), event.getPayload(), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        if (confirm == null || !confirm.isAck()) {
            throw new IllegalStateException("broker did not confirm event: "
                    + (confirm == null ? "null" : confirm.getReason()));
        }
    }

    private String abbreviate(String message) {
        if (message == null) {
            return "unknown publish error";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
