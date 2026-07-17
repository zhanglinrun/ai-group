package com.aigroup.paymall.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 消息发送
 * @create 2024-03-30 12:40
 */
@Slf4j
@Component
public class EventPublisher {

    private final ConfirmedRabbitPublisher confirmedRabbitPublisher;

    public EventPublisher(ConfirmedRabbitPublisher confirmedRabbitPublisher) {
        this.confirmedRabbitPublisher = confirmedRabbitPublisher;
    }

    @Value("${spring.rabbitmq.config.producer.topic_order_pay_success.exchange}")
    private String exchangeName;

    public void publish(String correlationKey, String routingKey, String message) {
        try {
            confirmedRabbitPublisher.publish(exchangeName, routingKey, message, correlationKey);
        } catch (Exception e) {
            log.error("发送MQ消息失败 routingKey:{} message:{}", routingKey, message, e);
            throw e;
        }
    }

}
