package com.aigroup.paymall.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pay-side event publisher for order-pay-success messages. Delegates to
 * {@link ConfirmedRabbitPublisher} and only completes after the broker confirms.
 */
@Slf4j
@Component
public class EventPublisher {

    private final ConfirmedRabbitPublisher confirmedRabbitPublisher;

    public EventPublisher(ConfirmedRabbitPublisher confirmedRabbitPublisher) {
        this.confirmedRabbitPublisher = confirmedRabbitPublisher;
    }

    public void publish(String correlationKey, String topic, String message) {
        try {
            confirmedRabbitPublisher.publish(topic, message, correlationKey);
        } catch (Exception e) {
            log.error("发送RabbitMQ消息失败 routingKey:{} message:{}", topic, message, e);
            throw e;
        }
    }

}
