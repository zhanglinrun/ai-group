package com.aigroup.paymall.infrastructure.event;

import com.aigroup.paymall.types.event.TradeCompletedEvent;
import com.aigroup.paymall.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BenefitEventPublisher {

    private final ConfirmedRabbitPublisher confirmedRabbitPublisher;

    public BenefitEventPublisher(ConfirmedRabbitPublisher confirmedRabbitPublisher) {
        this.confirmedRabbitPublisher = confirmedRabbitPublisher;
    }

    @Value("${spring.rabbitmq.routing.member-benefit:member.benefit.completed}")
    private String topic;

    public void publish(TradeCompletedEvent event) {
        String message = JsonUtils.toJson(event);
        try {
            confirmedRabbitPublisher.publish(topic, message, event.getEventId());
        } catch (Exception e) {
            log.error("发送权益事件失败 topic:{} message:{}", topic, message, e);
            throw e;
        }
    }

}
