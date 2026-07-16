package com.aigroup.groupbuy.trigger.listener;

import com.aigroup.groupbuy.domain.trade.model.valobj.TeamRefundSuccess;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei (bugstack.cn)
 * @description 团队退款成功消息监听器
 * @create 2025-03-08 13:49
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    /**
     * 消费团队退款消息并恢复团队锁定库存。
     * 消费异常会继续抛出，以触发 RabbitMQ 有界重试；重试耗尽后消息进入死信队列。
     * 重复消息的幂等处理由领域服务负责。
     */
    // B1: queue declares dead-letter routing; combined with bounded listener retry
    // (default-requeue-rejected=false) poison messages land in the DLQ instead of
    // blocking the refund-recovery queue forever. If an old queue WITHOUT these
    // arguments already exists locally, delete it once so it can be re-declared
    // (RabbitMQ PRECONDITION_FAILED otherwise). See RabbitMQDlqConfig.
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(
                            value = "${spring.rabbitmq.config.producer.topic_team_refund.queue}",
                            arguments = {
                                    @Argument(name = "x-dead-letter-exchange", value = "${spring.rabbitmq.config.producer.dlx_exchange}"),
                                    @Argument(name = "x-dead-letter-routing-key", value = "${spring.rabbitmq.config.producer.topic_team_refund.dlq_routing_key}")
                            }
                    ),
                    exchange = @Exchange(value = "${spring.rabbitmq.config.producer.exchange}", type = ExchangeTypes.TOPIC),
                    key = "${spring.rabbitmq.config.producer.topic_team_refund.routing_key}"
            )
    )
    public void listener(String message) {
        log.info("receive message (team refund) - restore team lock stock:{}", message);
        TeamRefundSuccess teamRefundSuccess = JSON.parseObject(message, TeamRefundSuccess.class);
        try {
            tradeRefundOrderService.restoreTeamLockStock(teamRefundSuccess);
        } catch (Exception e) {
            log.error("receive message (team refund) - restore team lock stock failed:{}", message, e);
            // throw so the MQ listener retries; after retry is exhausted the message is dead-lettered
            throw new RuntimeException(e);
        }
    }

}
