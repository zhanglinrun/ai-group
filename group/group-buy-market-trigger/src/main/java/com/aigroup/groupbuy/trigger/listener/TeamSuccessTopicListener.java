package com.aigroup.groupbuy.trigger.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 结算完成消息监听（Kafka）。消费 group.team_success 主题。
 */
@Slf4j
@Component
public class TeamSuccessTopicListener {

    @KafkaListener(
            topics = "${spring.kafka.config.producer.topic_team_success.topic:group.team_success}",
            groupId = "${spring.kafka.config.consumer.group-id:group-buy-market}")
    public void listener(String message, Acknowledgment acknowledgment) {
        log.info("接收消息（组队成功）:{}", message);
        acknowledgment.acknowledge();
    }

}
