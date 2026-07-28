package com.aigroup.groupbuy.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code send().get(timeout)} blocks until the broker ACKs (acks=all).
 * NACK, timeout and interruption all keep the outbox row retryable.
 */
@Slf4j
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long confirmTimeoutMillis;

    public EventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${spring.kafka.config.producer.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        if (confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Kafka publisher confirm timeout must be positive");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public void publish(String routingKey, String message) {
        publish(routingKey, message, routingKey);
    }

    /**
     * Returns only after the Kafka broker ACKs the message (acks=all + idempotent producer).
     * The notify-task caller may mark its local outbox row successful only after this
     * method returns; NACK, timeout and interruption all keep the task retryable.
     */
    public void publish(String topic, String message, String correlationKey) {
        String key = (correlationKey == null || correlationKey.isBlank()) ? topic : correlationKey;
        try {
            SendResult<String, String> result = kafkaTemplate.send(topic, key, message)
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null || result.getRecordMetadata() == null
                    || !topic.equals(result.getRecordMetadata().topic())
                    || result.getRecordMetadata().partition() < 0
                    || result.getRecordMetadata().offset() < 0) {
                throw publishFailure(topic, "broker returned invalid record metadata", null);
            }
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
        String error = "Kafka publish failed topic=" + topic + ": " + reason;
        return cause == null ? new IllegalStateException(error) : new IllegalStateException(error, cause);
    }

}
