package com.aigroup.paymall.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Pay-side event publisher for order-pay-success messages. Delegates to
 * {@link ConfirmedKafkaPublisher} which blocks until the broker ACKs (acks=all).
 */
@Slf4j
@Component
public class EventPublisher {

    private final ConfirmedKafkaPublisher confirmedKafkaPublisher;

    public EventPublisher(ConfirmedKafkaPublisher confirmedKafkaPublisher) {
        this.confirmedKafkaPublisher = confirmedKafkaPublisher;
    }

    public void publish(String correlationKey, String topic, String message) {
        try {
            confirmedKafkaPublisher.publish(topic, message, correlationKey);
        } catch (Exception e) {
            log.error("发送Kafka消息失败 topic:{} message:{}", topic, message, e);
            throw e;
        }
    }

}
