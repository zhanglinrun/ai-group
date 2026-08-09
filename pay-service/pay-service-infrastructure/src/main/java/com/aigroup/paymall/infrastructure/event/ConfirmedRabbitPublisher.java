package com.aigroup.paymall.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Publishes an outbox message and waits for the RabbitMQ publisher confirm. */
@Slf4j
@Component
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final long confirmTimeoutMillis;

    public ConfirmedRabbitPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.event-exchange:xiongdoctor.events}") String exchange,
            @Value("${spring.rabbitmq.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        if (confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Rabbit publisher confirm timeout must be positive");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public void publish(String routingKey, String payload, String correlationKey) {
        String key = correlationKey == null || correlationKey.isBlank() ? routingKey : correlationKey;
        CorrelationData correlationData = new CorrelationData(key);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw publishFailure(routingKey,
                        confirm == null ? "broker returned no confirm" : confirm.getReason(), null);
            }
            log.debug("rabbit publish confirmed exchange={} routingKey={} correlationKey={}",
                    exchange, routingKey, key);
        } catch (java.util.concurrent.TimeoutException e) {
            throw publishFailure(routingKey, "rabbit publish timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailure(routingKey, "interrupted while awaiting rabbit confirm", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw publishFailure(routingKey, "rabbit publish failed", cause);
        }
    }

    private IllegalStateException publishFailure(String routingKey, String reason, Throwable cause) {
        String message = "Rabbit publish failed routingKey=" + routingKey + ": " + reason;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
