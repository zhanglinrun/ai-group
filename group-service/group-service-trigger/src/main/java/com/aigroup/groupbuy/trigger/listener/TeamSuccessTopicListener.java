package com.aigroup.groupbuy.trigger.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 结算完成消息监听（RabbitMQ topic）。
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {

    @RabbitListener(queues = "group-service.team-success")
    public void listener(String message) {
        log.info("接收消息（组队成功）:{}", message);
    }

}
