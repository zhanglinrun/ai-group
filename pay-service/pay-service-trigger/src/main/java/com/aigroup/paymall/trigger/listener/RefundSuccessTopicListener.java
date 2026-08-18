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
 * Group team_refund listener: executes the Alipay refund for orders parked in WAIT_REFUND.
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    @KafkaListener(
            topics = "${ai-group.kafka.topics.team-refund:group.team_refund}",
            groupId = "pay-service")
    public void consume(String message, Acknowledgment ack) {
        listener(message);
        ack.acknowledge();
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
