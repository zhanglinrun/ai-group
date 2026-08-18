package com.aigroup.paymall.infrastructure.event;

import com.aigroup.messaging.ConfirmedKafkaPublisher;
import com.aigroup.paymall.types.common.JsonUtils;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BenefitEventPublisher {

    private final ConfirmedKafkaPublisher kafkaPublisher;

    public BenefitEventPublisher(ConfirmedKafkaPublisher kafkaPublisher) {
        this.kafkaPublisher = kafkaPublisher;
    }

    @Value("${ai-group.kafka.topics.member-benefit:member.benefit.completed}")
    private String topic;

    public void publish(TradeCompletedEvent event) {
        String message = JsonUtils.toJson(event);
        try {
            kafkaPublisher.publish(topic, event.getOrderId(), message);
        } catch (Exception e) {
            log.error("发送权益事件失败 topic:{} message:{}", topic, message, e);
            throw e;
        }
    }

}
