package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.api.dto.NotifyRequestDTO;
import com.aigroup.paymall.domain.order.service.IOrderService;
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
 * Group team_success message listener: marks the order MARKET settled.
 *
 * @author Fuzhengwei bugstack.cn
 * @create 2025-03-08 13:49
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    // C5: dead-letter routing on the consumer queue, see RefundSuccessTopicListener
    // for the rebuild note (delete a pre-existing queue without these arguments once).
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(
                            value = "${spring.rabbitmq.config.consumer.topic_team_success.queue}",
                            arguments = {
                                    @Argument(name = "x-dead-letter-exchange", value = "${spring.rabbitmq.config.consumer.dlx_exchange}"),
                                    @Argument(name = "x-dead-letter-routing-key", value = "${spring.rabbitmq.config.consumer.topic_team_success.dlq_routing_key}")
                            }
                    ),
                    exchange = @Exchange(value = "${spring.rabbitmq.config.consumer.topic_team_success.exchange}", type = ExchangeTypes.TOPIC),
                    key = "${spring.rabbitmq.config.consumer.topic_team_success.routing_key}"
            )
    )
    public void listener(String message) {
        try {
            NotifyRequestDTO requestDTO = JSON.parseObject(message, NotifyRequestDTO.class);
            log.info("team success callback, start settlement {}", JSON.toJSONString(requestDTO));
            orderService.changeOrderMarketSettlement(requestDTO.getOutTradeNoList(), requestDTO.getBonusQuota());
        } catch (Exception e) {
            log.error("team success callback, settlement failed {}", message, e);
            throw e;
        }
    }

}
