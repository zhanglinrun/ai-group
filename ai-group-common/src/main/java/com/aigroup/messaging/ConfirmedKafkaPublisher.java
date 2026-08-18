package com.aigroup.messaging;

import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Outbox publisher: wait for the Kafka broker ACK before the caller marks a row sent.
 * Timeout, interrupt and send failure all leave the outbox retryable.
 */
public class ConfirmedKafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long ackTimeoutMillis;

    public ConfirmedKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate, long ackTimeoutMillis) {
        if (ackTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Kafka publisher ack timeout must be positive");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeoutMillis = ackTimeoutMillis;
    }

    public void publish(String topic, String key, String payload) {
        String partitionKey = (key == null || key.isBlank()) ? topic : key;
        try {
            kafkaTemplate.send(topic, partitionKey, payload).get(ackTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw publishFailure(topic, "kafka publish timed out after " + ackTimeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailure(topic, "interrupted while awaiting kafka ack", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw publishFailure(topic, "kafka publish failed", cause);
        }
    }

    private IllegalStateException publishFailure(String topic, String reason, Throwable cause) {
        String message = "Kafka publish failed topic=" + topic + ": " + reason;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
