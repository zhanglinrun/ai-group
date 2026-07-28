package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.api.dto.TeamRefundSuccessRequestDTO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.types.enums.ResponseCode;
import com.aigroup.paymall.types.exception.AppException;
import com.aigroup.paymall.types.common.JsonUtils;
import com.alipay.api.AlipayApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Group team_refund message listener (Kafka): executes the alipay refund for orders
 * parked in WAIT_REFUND. 消费 group.team_refund 主题。
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    @KafkaListener(
            topics = "${spring.kafka.config.consumer.topic_team_refund.topic:group.team_refund}",
            groupId = "${spring.kafka.config.consumer.group-id:s-pay-mall-ddd}")
    public void listener(String message, Acknowledgment acknowledgment) {
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
            acknowledgment.acknowledge();
        } catch (AlipayApiException ex) {
            throw new RuntimeException(ex);
        } catch (Exception e) {
            log.error("team refund callback, refund failed {}", message, e);
            throw e;
        }
    }

}
