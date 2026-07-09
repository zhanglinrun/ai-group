package com.aigroup.paymall.infrastructure.event;

import com.aigroup.paymall.types.event.TradeCompletedEvent;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BenefitEventPublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${spring.rabbitmq.config.producer.member_benefit.exchange}")
    private String exchangeName;

    @Value("${spring.rabbitmq.config.producer.member_benefit.routing_key}")
    private String routingKey;

    public void publish(TradeCompletedEvent event) {
        String message = JSON.toJSONString(event);
        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, message, m -> {
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return m;
            });
        } catch (Exception e) {
            log.error("发送权益事件失败 routingKey:{} message:{}", routingKey, message, e);
            throw e;
        }
    }

}
