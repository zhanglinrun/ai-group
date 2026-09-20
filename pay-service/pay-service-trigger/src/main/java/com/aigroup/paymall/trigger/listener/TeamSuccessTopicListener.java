package com.aigroup.paymall.trigger.listener;

import com.aigroup.paymall.api.dto.NotifyRequestDTO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.domain.order.model.entity.TeamSettlementMember;
import com.aigroup.paymall.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

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

    @KafkaListener(
            topics = "${ai-group.kafka.topics.team-success:group.team_success}.DLT",
            groupId = "pay-service-dlt",
            containerFactory = "dltKafkaListenerContainerFactory")
    public void consumeDlt(String message, Acknowledgment ack) {
        listener(message);
        ack.acknowledge();
    }

    public void listener(String message) {
        try {
            NotifyRequestDTO requestDTO = JsonUtils.parseObject(message, NotifyRequestDTO.class);
            if (requestDTO == null || blank(requestDTO.getTeamId())
                    || requestDTO.getActivityId() == null || requestDTO.getActivityId() <= 0
                    || requestDTO.getMembers() == null || requestDTO.getMembers().isEmpty()
                    || requestDTO.getMembers().stream().anyMatch(member -> member == null
                    || blank(member.getUserId()) || blank(member.getSource())
                    || blank(member.getChannel()) || blank(member.getOutTradeNo()))) {
                throw new IllegalArgumentException("formed team member identity is required");
            }
            List<TeamSettlementMember> members = requestDTO.getMembers().stream().map(member ->
                    new TeamSettlementMember(member.getUserId(), member.getSource(),
                            member.getChannel(), member.getOutTradeNo())).toList();
            orderService.changeOrderMarketSettlement(requestDTO.getTeamId(), requestDTO.getActivityId(), members);
        } catch (Exception e) {
            log.error("team success callback, settlement failed {}", message, e);
            throw e;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
