package com.aigroup.groupbuy.infrastructure.adapter.port;

import com.aigroup.groupbuy.domain.trade.adapter.port.ITradePort;
import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.NotifyTypeEnumVO;
import com.aigroup.groupbuy.infrastructure.event.EventPublisher;
import com.aigroup.groupbuy.infrastructure.gateway.GroupBuyNotifyService;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import com.aigroup.groupbuy.types.enums.NotifyTaskHTTPEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @description 交易接口服务
 * @create 2025-01-31 13:34
 */
@Slf4j
@Service
public class TradePort implements ITradePort {

    @Resource
    private GroupBuyNotifyService groupBuyNotifyService;
    @Resource
    private IRedisService redisService;
    @Resource
    private EventPublisher publisher;

    @Override
    public String groupBuyNotify(NotifyTaskEntity notifyTask) throws Exception {
        RLock lock = redisService.getLock(notifyTask.lockKey());
        try {
            // 多实例会同时扫通知任务，先抢锁再回调，避免同一任务被执行多次
            if (lock.tryLock(3, 0, TimeUnit.SECONDS)) {
                try {
                    // 回调方式 HTTP
                    if (NotifyTypeEnumVO.HTTP.getCode().equals(notifyTask.getNotifyType())) {
                        // 无效的 notifyUrl 则直接返回成功
                        if (StringUtils.isBlank(notifyTask.getNotifyUrl()) || "暂无".equals(notifyTask.getNotifyUrl())) {
                            return NotifyTaskHTTPEnumVO.SUCCESS.getCode();
                        }
                        String notifyResult = groupBuyNotifyService.groupBuyNotify(notifyTask.getNotifyUrl(), notifyTask.getParameterJson());
                        // 仅当返回体为 success（忽略大小写）时才算回调成功，否则返回 ERROR 交由既有任务重试
                        if (NotifyTaskHTTPEnumVO.SUCCESS.getCode().equalsIgnoreCase(notifyResult)) {
                            return NotifyTaskHTTPEnumVO.SUCCESS.getCode();
                        }
                        log.warn("拼团 HTTP 回调返回非 success，等待重试 teamId:{} notifyResult:{}", notifyTask.getTeamId(), notifyResult);
                        return NotifyTaskHTTPEnumVO.ERROR.getCode();
                    }

                    // 回调方式 MQ
                    if (NotifyTypeEnumVO.MQ.getCode().equals(notifyTask.getNotifyType())) {
                        publisher.publish(
                                notifyTask.getNotifyMQ(),
                                notifyTask.getParameterJson(),
                                notifyTask.getUuid());
                        return NotifyTaskHTTPEnumVO.SUCCESS.getCode();
                    }
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
