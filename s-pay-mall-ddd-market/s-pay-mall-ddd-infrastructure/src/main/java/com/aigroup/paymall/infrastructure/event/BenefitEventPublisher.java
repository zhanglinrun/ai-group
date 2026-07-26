package com.aigroup.paymall.infrastructure.event;

import com.aigroup.paymall.types.event.TradeCompletedEvent;
import com.aigroup.paymall.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BenefitEventPublisher {

    private final ConfirmedKafkaPublisher confirmedKafkaPublisher;

    public BenefitEventPublisher(ConfirmedKafkaPublisher confirmedKafkaPublisher) {
        this.confirmedKafkaPublisher = confirmedKafkaPublisher;
    }

    @Value("${spring.kafka.config.producer.member_benefit.topic:member.benefit.completed}")
    private String topic;

    public void publish(TradeCompletedEvent event) {
        String message = JsonUtils.toJson(event);
        try {
            confirmedKafkaPublisher.publish(topic, message, event.getEventId());
        } catch (Exception e) {
            log.error("发送权益事件失败 topic:{} message:{}", topic, message, e);
            throw e;
        }
    }

}
