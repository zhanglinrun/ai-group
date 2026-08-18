package com.aigroup.groupbuy.infrastructure.event;

import com.aigroup.messaging.ConfirmedKafkaPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Group-side Kafka publisher for notify_task. Completes only after the broker ACK.
 */
@Slf4j
@Component
public class EventPublisher {

    private final ConfirmedKafkaPublisher kafkaPublisher;

    public EventPublisher(ConfirmedKafkaPublisher kafkaPublisher) {
        this.kafkaPublisher = kafkaPublisher;
    }

    public void publish(String topic, String payload) {
        publish(topic, topic, payload);
    }

    public void publish(String topic, String key, String payload) {
        try {
            kafkaPublisher.publish(topic, key, payload);
        } catch (Exception e) {
            log.error("发送 Kafka 消息失败 topic:{} key:{} message:{}", topic, key, payload, e);
            throw e;
        }
    }

}
