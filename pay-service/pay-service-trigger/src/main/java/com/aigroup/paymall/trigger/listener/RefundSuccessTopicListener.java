package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.api.dto.TeamRefundSuccessRequestDTO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.types.enums.ResponseCode;
import com.aigroup.paymall.types.exception.AppException;
import com.aigroup.paymall.types.common.JsonUtils;
import com.alipay.api.AlipayApiException;
import lombok.extern.slf4j.Slf4j;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Group team_refund message listener (RabbitMQ): executes the alipay refund for orders
 * parked in WAIT_REFUND. 消费 group.team_refund 主题。
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    @RabbitListener(queues = "pay-service.team-refund", ackMode = "MANUAL")
    public void consume(String message, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws Exception {
        listener(message);
        channel.basicAck(tag, false);
    }

    public void listener(String message) {
        try {
            log.info("team refund callback, start refund {}", message);
            TeamRefundSuccessRequestDTO requestDTO = JsonUtils.parseObject(message, TeamRefundSuccessRequestDTO.class);
            String type = requestDTO.getType();
            if ("paid_unformed".equals(type) || "paid_formed".equals(type)) {
                boolean success = orderService.refundPayOrder(requestDTO.getUserId(), requestDTO.getOutTradeNo());
                if (!success) {
                    throw new AppException(ResponseCode.UN_ERROR.getCode(),
                            "refund pay order failed userId:" + requestDTO.getUserId() + " outTradeNo:" + requestDTO.getOutTradeNo());
                }
            }
        } catch (AlipayApiException ex) {
            throw new RuntimeException(ex);
        } catch (Exception e) {
            log.error("team refund callback, refund failed {}", message, e);
            throw e;
        }
    }

}
