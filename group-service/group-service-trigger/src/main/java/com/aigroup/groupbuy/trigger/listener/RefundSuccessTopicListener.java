package com.aigroup.groupbuy.trigger.listener;

import com.aigroup.groupbuy.domain.trade.model.valobj.TeamRefundSuccess;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 团队退款成功消息监听器：恢复 Redis 锁定库存。
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    /**
     * 消费团队退款消息并恢复团队锁定库存。
     * 业务成功后再 ack；失败由 DefaultErrorHandler 有限重试，耗尽后发到 topic.DLT。
     * 重复消息的幂等处理由领域服务负责。
     */
    @KafkaListener(
            topics = "${ai-group.kafka.topics.team-refund:group.team_refund}",
            groupId = "group-service")
    public void consume(String message, Acknowledgment ack) {
        listener(message);
        ack.acknowledge();
    }

    @KafkaListener(
            topics = "${ai-group.kafka.topics.team-refund:group.team_refund}.DLT",
            groupId = "group-service-dlt",
            containerFactory = "dltKafkaListenerContainerFactory")
    public void consumeDlt(String message, Acknowledgment ack) {
        try {
            listener(message);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("kafka.dlt.exhausted topic=group.team_refund.DLT payload={}", message, e);
            ack.acknowledge();
        }
    }

    public void listener(String message) {
        log.info("receive message (team refund) - restore team lock stock:{}", message);
        TeamRefundSuccess teamRefundSuccess = JsonUtils.parseObject(message, TeamRefundSuccess.class);
        try {
            tradeRefundOrderService.restoreTeamLockStock(teamRefundSuccess);
        } catch (Exception e) {
            log.error("receive message (team refund) - restore team lock stock failed:{}", message, e);
            throw new RuntimeException(e);
        }
    }

}
