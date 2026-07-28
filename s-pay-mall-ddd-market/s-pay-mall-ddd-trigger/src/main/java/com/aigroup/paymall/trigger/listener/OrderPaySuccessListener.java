package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.domain.goods.service.IGoodsService;
import com.aigroup.paymall.domain.order.adapter.event.PaySuccessMessageEvent;
import com.aigroup.paymall.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Pay success message listener (Kafka): simulated shipping, moves the order to DEAL_DONE.
 * 消费 pay.order_pay_success 主题。
 */
@Slf4j
@Component
public class OrderPaySuccessListener {

    @Resource
    private IGoodsService goodsService;

    @KafkaListener(
            topics = "${spring.kafka.config.producer.topic_order_pay_success.topic:pay.order_pay_success}",
            groupId = "${spring.kafka.config.consumer.group-id:s-pay-mall-ddd}")
    public void listener(String paySuccessMessageJson, Acknowledgment acknowledgment) {
        try {
            log.info("pay success message received {}", paySuccessMessageJson);

            PaySuccessMessageEvent.PaySuccessMessage paySuccessMessage = JsonUtils.parseObject(paySuccessMessageJson, PaySuccessMessageEvent.PaySuccessMessage.class);

            goodsService.changeOrderDealDone(paySuccessMessage.getTradeNo());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("pay success message handling failed {}", paySuccessMessageJson, e);
            throw e;
        }
    }

}
