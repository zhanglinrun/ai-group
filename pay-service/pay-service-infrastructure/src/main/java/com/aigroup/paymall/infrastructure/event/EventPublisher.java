package com.aigroup.paymall.infrastructure.event;

import com.aigroup.messaging.ConfirmedKafkaPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pay-side Kafka publisher for order-pay-success. Completes only after the broker ACK.
 */
@Slf4j
@Component
public class EventPublisher {

    private final ConfirmedKafkaPublisher kafkaPublisher;

    public EventPublisher(ConfirmedKafkaPublisher kafkaPublisher) {
        this.kafkaPublisher = kafkaPublisher;
    }

    public void publish(String topic, String key, String message) {
        try {
            kafkaPublisher.publish(topic, key, message);
        } catch (Exception e) {
            log.error("发送 Kafka 消息失败 topic:{} key:{} message:{}", topic, key, message, e);
            throw e;
        }
    }

}
