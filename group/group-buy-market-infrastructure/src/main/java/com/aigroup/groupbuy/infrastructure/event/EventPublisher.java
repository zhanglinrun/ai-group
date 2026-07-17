package com.aigroup.groupbuy.infrastructure.event;

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
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 消息发送
 * @create 2024-03-30 12:40
 */
@Component
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;
    private final long confirmTimeoutMillis;

    public EventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.config.producer.exchange}") String exchangeName,
            @Value("${spring.rabbitmq.config.producer.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        if (confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Rabbit publisher confirm timeout must be positive");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public void publish(String routingKey, String message) {
        publish(routingKey, message, routingKey);
    }

    /**
     * Returns only after the broker ACKs a persistent, routable message. The
     * notify-task caller may mark its local outbox row successful only after
     * this method returns; NACK, return, timeout and interruption all keep the
     * task retryable.
     */
    public void publish(String routingKey, String message, String correlationKey) {
        String attemptId = (correlationKey == null || correlationKey.isBlank())
                ? UUID.randomUUID().toString()
                : correlationKey + ":" + UUID.randomUUID();
        CorrelationData correlationData = new CorrelationData(attemptId);
        rabbitTemplate.convertAndSend(exchangeName, routingKey, message, payload -> {
            payload.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return payload;
        }, correlationData);

        CorrelationData.Confirm confirm = awaitConfirm(correlationData, routingKey);
        ReturnedMessage returned = correlationData.getReturned();
        if (!confirm.isAck()) {
            throw publishFailure(routingKey,
                    "broker NACK" + (confirm.getReason() == null ? "" : ": " + confirm.getReason()), null);
        }
        if (returned != null) {
            throw publishFailure(routingKey,
                    "message returned as unroutable, replyCode=" + returned.getReplyCode()
                            + ", replyText=" + returned.getReplyText(), null);
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlationData, String routingKey) {
        try {
            return correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw publishFailure(routingKey,
                    "publisher confirm timed out after " + confirmTimeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailure(routingKey, "interrupted while awaiting publisher confirm", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw publishFailure(routingKey, "publisher confirm failed", cause);
        }
    }

    private IllegalStateException publishFailure(String routingKey, String reason, Throwable cause) {
        String error = "RabbitMQ publish failed exchange=" + exchangeName
                + " routingKey=" + routingKey + ": " + reason;
        return cause == null ? new IllegalStateException(error) : new IllegalStateException(error, cause);
    }

}
