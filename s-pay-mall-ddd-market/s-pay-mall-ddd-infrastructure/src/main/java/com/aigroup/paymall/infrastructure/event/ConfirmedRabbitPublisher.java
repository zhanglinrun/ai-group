package com.aigroup.paymall.infrastructure.event;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a persistent RabbitMQ message and only returns after the broker has
 * ACKed it and confirmed that it was routable.
 *
 * <p>Spring AMQP populates {@link CorrelationData#getReturned()} before its
 * publisher-confirm future completes. Therefore an ACK plus a {@code null}
 * returned message is the success gate; every other outcome is reported to
 * the caller so its retry or compensation path can keep the operation pending.</p>
 */
@Component
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    public ConfirmedRabbitPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.config.producer.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        if (confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Rabbit publisher confirm timeout must be positive");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public void publish(String exchange, String routingKey, String payload, String correlationKey) {
        // CorrelationData ids must be unique while returns are enabled. Keep the
        // stable business key for diagnostics, but suffix every publish attempt.
        String attemptId = (correlationKey == null || correlationKey.isBlank())
                ? UUID.randomUUID().toString()
                : correlationKey + ":" + UUID.randomUUID();
        CorrelationData correlationData = new CorrelationData(attemptId);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        }, correlationData);

        CorrelationData.Confirm confirm = awaitConfirm(correlationData, exchange, routingKey);
        ReturnedMessage returned = correlationData.getReturned();
        if (!confirm.isAck()) {
            throw publishFailure(exchange, routingKey,
                    "broker NACK" + (confirm.getReason() == null ? "" : ": " + confirm.getReason()), null);
        }
        if (returned != null) {
            throw publishFailure(exchange, routingKey,
                    "message returned as unroutable, replyCode=" + returned.getReplyCode()
                            + ", replyText=" + returned.getReplyText(), null);
        }
    }

    private CorrelationData.Confirm awaitConfirm(
            CorrelationData correlationData, String exchange, String routingKey) {
        try {
            return correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw publishFailure(exchange, routingKey,
                    "publisher confirm timed out after " + confirmTimeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailure(exchange, routingKey, "interrupted while awaiting publisher confirm", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw publishFailure(exchange, routingKey, "publisher confirm failed", cause);
        }
    }

    private IllegalStateException publishFailure(
            String exchange, String routingKey, String reason, Throwable cause) {
        String message = "RabbitMQ publish failed exchange=" + exchange
                + " routingKey=" + routingKey + ": " + reason;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
