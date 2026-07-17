package com.aigroup.paymall.infrastructure.event;

import com.aigroup.paymall.types.event.TradeCompletedEvent;
import com.alibaba.fastjson.JSON;
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

    @Value("${spring.rabbitmq.config.producer.member_benefit.exchange}")
    private String exchangeName;

    @Value("${spring.rabbitmq.config.producer.member_benefit.routing_key}")
    private String routingKey;

    public void publish(TradeCompletedEvent event) {
        String message = JSON.toJSONString(event);
        try {
            confirmedRabbitPublisher.publish(exchangeName, routingKey, message, event.getEventId());
        } catch (Exception e) {
            log.error("发送权益事件失败 routingKey:{} message:{}", routingKey, message, e);
            throw e;
        }
    }

}
