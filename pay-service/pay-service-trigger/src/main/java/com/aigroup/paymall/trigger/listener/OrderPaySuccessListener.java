package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.domain.goods.service.IGoodsService;
import com.aigroup.paymall.domain.order.adapter.event.PaySuccessMessageEvent;
import com.aigroup.paymall.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Pay success message listener (RabbitMQ): simulated shipping, moves the order to DEAL_DONE.
 * 消费 pay.order_pay_success 主题。
 */
@Slf4j
@Component
public class OrderPaySuccessListener {

    @Resource
    private IGoodsService goodsService;

    @RabbitListener(queues = "pay-service.order-pay-success")
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
