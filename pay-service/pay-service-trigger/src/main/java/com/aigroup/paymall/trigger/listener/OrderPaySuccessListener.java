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
 * Pay success listener: simulated shipping, moves the order to DEAL_DONE.
 */
@Slf4j
@Component
public class OrderPaySuccessListener {

    @Resource
    private IGoodsService goodsService;

    @KafkaListener(
            topics = "${ai-group.kafka.topics.order-pay-success:pay.order_pay_success}",
            groupId = "pay-service")
    public void consume(String paySuccessMessageJson, Acknowledgment ack) {
        listener(paySuccessMessageJson);
        ack.acknowledge();
    }

    public void listener(String paySuccessMessageJson) {
        try {
            log.info("pay success message received {}", paySuccessMessageJson);

            PaySuccessMessageEvent.PaySuccessMessage paySuccessMessage = JsonUtils.parseObject(paySuccessMessageJson, PaySuccessMessageEvent.PaySuccessMessage.class);

            goodsService.changeOrderDealDone(paySuccessMessage.getTradeNo());
        } catch (Exception e) {
            log.error("pay success message handling failed {}", paySuccessMessageJson, e);
            throw e;
        }
    }

}
