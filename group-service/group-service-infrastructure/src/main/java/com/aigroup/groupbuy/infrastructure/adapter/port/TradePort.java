package com.aigroup.groupbuy.infrastructure.adapter.port;

import com.aigroup.groupbuy.domain.trade.adapter.port.ITradePort;
import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import com.aigroup.messaging.ConfirmedKafkaPublisher;
import com.aigroup.groupbuy.types.enums.NotifyTaskHTTPEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * Outbox dispatcher for notify_task: Kafka only. HTTP callbacks are retired.
 */
@Slf4j
@Service
public class TradePort implements ITradePort {

    @Resource
    private IRedisService redisService;
    @Resource
    private ConfirmedKafkaPublisher kafkaPublisher;

    @Value("${ai-group.kafka.topics.team-success:group.team_success}")
    private String defaultTeamSuccessTopic;

    @Override
    public String groupBuyNotify(NotifyTaskEntity notifyTask) throws Exception {
        RLock lock = redisService.getLock(notifyTask.lockKey());
        try {
            if (lock.tryLock(3, 0, TimeUnit.SECONDS)) {
                try {
                    String topic = StringUtils.isNotBlank(notifyTask.getNotifyMQ())
                            ? notifyTask.getNotifyMQ()
                            : defaultTeamSuccessTopic;
                    kafkaPublisher.publish(topic, notifyTask.getTeamId(), notifyTask.getParameterJson());
                    return NotifyTaskHTTPEnumVO.SUCCESS.getCode();
                } finally {
                    if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
            return NotifyTaskHTTPEnumVO.NULL.getCode();
        } catch (Exception e) {
            log.error("拼团回调通知异常，等待重试 teamId:{}", notifyTask.getTeamId(), e);
            return NotifyTaskHTTPEnumVO.ERROR.getCode();
        }
    }

}
