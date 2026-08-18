package com.aigroup.groupbuy.infrastructure.adapter.port;

import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.NotifyTypeEnumVO;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import com.aigroup.messaging.ConfirmedKafkaPublisher;
import com.aigroup.groupbuy.types.enums.NotifyTaskHTTPEnumVO;
import org.junit.Test;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradePortPublisherConfirmTest {

    @Test
    public void mqTaskRemainsRetryableWhenBrokerConfirmFails() throws Exception {
        IRedisService redisService = mock(IRedisService.class);
        RLock lock = mock(RLock.class);
        ConfirmedKafkaPublisher publisher = mock(ConfirmedKafkaPublisher.class);
        when(redisService.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3, 0, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isLocked()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalStateException("broker NACK"))
                .when(publisher).publish("topic.team_success", "team-1", "{}");

        TradePort tradePort = new TradePort();
        ReflectionTestUtils.setField(tradePort, "redisService", redisService);
        ReflectionTestUtils.setField(tradePort, "kafkaPublisher", publisher);
        ReflectionTestUtils.setField(tradePort, "defaultTeamSuccessTopic", "group.team_success");

        String result = tradePort.groupBuyNotify(NotifyTaskEntity.builder()
                .teamId("team-1")
                .notifyType(NotifyTypeEnumVO.MQ.getCode())
                .notifyMQ("topic.team_success")
                .parameterJson("{}")
                .uuid("notify-1")
                .build());

        assertEquals(NotifyTaskHTTPEnumVO.ERROR.getCode(), result);
        verify(lock).unlock();
    }
}
