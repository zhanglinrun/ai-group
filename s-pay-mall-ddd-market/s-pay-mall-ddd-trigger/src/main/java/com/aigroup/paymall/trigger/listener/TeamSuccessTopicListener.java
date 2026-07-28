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
 * Group team_success message listener (Kafka): marks the order MARKET settled.
 * 消费 group.team_success 主题。
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {

    @Resource
    private IOrderService orderService;

    @KafkaListener(
            topics = "${spring.kafka.config.consumer.topic_team_success.topic:group.team_success}",
            groupId = "${spring.kafka.config.consumer.group-id:s-pay-mall-ddd}")
    public void listener(String message, Acknowledgment acknowledgment) {
        try {
            NotifyRequestDTO requestDTO = JsonUtils.parseObject(message, NotifyRequestDTO.class);
            log.info("team success callback, start settlement {}", JsonUtils.toJson(requestDTO));
            orderService.changeOrderMarketSettlement(requestDTO.getOutTradeNoList(), requestDTO.getBonusQuota());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("team success callback, settlement failed {}", message, e);
            throw e;
        }
    }

}
