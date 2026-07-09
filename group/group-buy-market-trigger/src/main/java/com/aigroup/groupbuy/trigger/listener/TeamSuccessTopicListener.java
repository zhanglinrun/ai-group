package com.aigroup.groupbuy.trigger.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 结算完成消息监听
 * @create 2025-03-08 13:49
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {

    // B1: queue declares dead-letter routing; combined with bounded listener retry
    // (default-requeue-rejected=false) poison messages land in the DLQ instead of
    // being requeued forever. If an old queue WITHOUT these arguments already
    // exists locally, delete it once so it can be re-declared (RabbitMQ
    // PRECONDITION_FAILED otherwise). See RabbitMQDlqConfig.
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(
                            value = "${spring.rabbitmq.config.producer.topic_team_success.queue}",
                            arguments = {
                                    @Argument(name = "x-dead-letter-exchange", value = "${spring.rabbitmq.config.producer.dlx_exchange}"),
                                    @Argument(name = "x-dead-letter-routing-key", value = "${spring.rabbitmq.config.producer.topic_team_success.dlq_routing_key}")
                            }
                    ),
                    exchange = @Exchange(value = "${spring.rabbitmq.config.producer.exchange}", type = ExchangeTypes.TOPIC),
                    key = "${spring.rabbitmq.config.producer.topic_team_success.routing_key}"
            )
    )
    public void listener(String message) {
        log.info("接收消息（组队成功）:{}", message);
    }

}
