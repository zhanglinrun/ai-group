package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.api.dto.NotifyRequestDTO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Group team_success listener: marks the order MARKET settled.
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    @KafkaListener(
            topics = "${ai-group.kafka.topics.team-success:group.team_success}",
            groupId = "pay-service")
    public void consume(String message, Acknowledgment ack) {
        listener(message);
        ack.acknowledge();
    }

    public void listener(String message) {
        try {
            NotifyRequestDTO requestDTO = JsonUtils.parseObject(message, NotifyRequestDTO.class);
            log.info("team success callback, start settlement {}", JsonUtils.toJson(requestDTO));
            orderService.changeOrderMarketSettlement(requestDTO.getOutTradeNoList());
        } catch (Exception e) {
            log.error("team success callback, settlement failed {}", message, e);
            throw e;
        }
    }

}
