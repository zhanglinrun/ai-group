package com.aigroup.groupbuy.infrastructure.event;

import com.aigroup.groupbuy.types.event.BaseEvent;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 消息发送
 * @create 2024-03-30 12:40
 */
@Slf4j
@Component
public class EventPublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${spring.rabbitmq.config.producer.exchange}")
    private String exchangeName;

    /**
     * Register publisher-confirm / returns callbacks so a broker NACK or an
     * unroutable message surfaces in logs (publisher-confirm-type=correlated).
     * Guaranteed delivery itself is provided by the local message table +
     * GroupBuyNotifyJob compensation.
     */
    @PostConstruct
    public void initConfirmCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("MQ publish not confirmed (NACK), correlationData:{} cause:{}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "MQ message returned (unroutable), exchange:{} routingKey:{} replyText:{}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    public void publish(String routingKey, String message) {
        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, message, m -> {
                // 持久化消息配置
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return m;
            });
        } catch (Exception e) {
            log.error("发送MQ消息失败 team_success message:{}", message, e);
            throw e;
        }
    }

}
