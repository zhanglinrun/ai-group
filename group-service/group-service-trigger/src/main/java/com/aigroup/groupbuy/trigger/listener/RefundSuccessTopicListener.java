package com.aigroup.groupbuy.trigger.listener;

import com.aigroup.groupbuy.domain.trade.model.valobj.TeamRefundSuccess;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 团队退款成功消息监听器（RabbitMQ topic）。
 */
@Slf4j
@Component
public class RefundSuccessTopicListener {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    /**
     * 消费团队退款消息并恢复团队锁定库存。
     * 消费异常会继续抛出，以触发 RabbitMQ 消费者重试；重试耗尽后消息进入死信队列。
     * 重复消息的幂等处理由领域服务负责。
     */
    @RabbitListener(queues = "group-service.team-refund")
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
