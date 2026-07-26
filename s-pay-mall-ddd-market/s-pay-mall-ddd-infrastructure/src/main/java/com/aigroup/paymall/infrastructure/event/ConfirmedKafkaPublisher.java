package com.aigroup.paymall.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a Kafka message and only returns after the broker has ACKed it (acks=all
 * + idempotent producer). Replaces the legacy RabbitMQ publisher-confirm + returns
 * pattern; {@code send().get(timeout)} provides the same "only return after broker
 * confirms" semantics. Every failure outcome is reported to the caller so its retry
 * or compensation path can keep the operation pending.
 */
@Slf4j
@Component
public class ConfirmedKafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long confirmTimeoutMillis;

    public ConfirmedKafkaPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${spring.kafka.config.producer.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        if (confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Kafka publisher confirm timeout must be positive");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /**
     * @param topic           Kafka topic to send to
     * @param payload         message body
     * @param correlationKey  partition key for ordering / idempotency tracking
     */
    public void publish(String topic, String payload, String correlationKey) {
        String key = (correlationKey == null || correlationKey.isBlank()) ? topic : correlationKey;
        try {
            SendResult<String, String> result = kafkaTemplate.send(topic, key, payload)
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            log.debug("kafka publish ok topic={} partition={} offset={}",
                    topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (TimeoutException e) {
            throw publishFailure(topic, "kafka send timed out after " + confirmTimeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailure(topic, "interrupted while awaiting kafka ack", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw publishFailure(topic, "kafka send failed", cause);
        }
    }

    private IllegalStateException publishFailure(String topic, String reason, Throwable cause) {
        String message = "Kafka publish failed topic=" + topic + ": " + reason;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
