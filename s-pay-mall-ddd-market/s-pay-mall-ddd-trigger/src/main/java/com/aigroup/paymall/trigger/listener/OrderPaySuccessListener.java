package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.domain.goods.service.IGoodsService;
import com.aigroup.paymall.domain.order.adapter.event.PaySuccessMessageEvent;
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
 * Pay success message listener: simulated shipping, moves the order to DEAL_DONE.
 *
 * @author Fuzhengwei bugstack.cn
 * @create 2024-09-30 09:52
 */
@Slf4j
@Component
public class OrderPaySuccessListener {

    @Resource
    private IGoodsService goodsService;

    // C5: dead-letter routing on the consumer queue, see RefundSuccessTopicListener
    // for the rebuild note (delete a pre-existing queue without these arguments once).
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(
                            value = "${spring.rabbitmq.config.consumer.topic_order_pay_success.queue}",
                            arguments = {
                                    @Argument(name = "x-dead-letter-exchange", value = "${spring.rabbitmq.config.consumer.dlx_exchange}"),
                                    @Argument(name = "x-dead-letter-routing-key", value = "${spring.rabbitmq.config.consumer.topic_order_pay_success.dlq_routing_key}")
                            }
                    ),
                    exchange = @Exchange(value = "${spring.rabbitmq.config.consumer.topic_order_pay_success.exchange}", type = ExchangeTypes.TOPIC),
                    key = "${spring.rabbitmq.config.consumer.topic_order_pay_success.routing_key}"
            )
    )
    public void listener(String paySuccessMessageJson) {
        try {
            log.info("pay success message received {}", paySuccessMessageJson);

            PaySuccessMessageEvent.PaySuccessMessage paySuccessMessage = JSON.parseObject(paySuccessMessageJson, PaySuccessMessageEvent.PaySuccessMessage.class);

            // simulated shipping (deliver goods / activate account), then DEAL_DONE
            goodsService.changeOrderDealDone(paySuccessMessage.getTradeNo());
        } catch (Exception e) {
            log.error("pay success message handling failed {}", paySuccessMessageJson, e);
            throw e;
        }
    }

}
