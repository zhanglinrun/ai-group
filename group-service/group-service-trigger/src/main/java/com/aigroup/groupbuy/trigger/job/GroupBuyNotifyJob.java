package com.aigroup.groupbuy.trigger.job;

import com.aigroup.groupbuy.domain.trade.service.ITradeTaskService;
import com.aigroup.groupbuy.types.common.JsonUtils;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 拼团完结回调通知任务。调度由 XXL-JOB admin 中心集中管理（cron: 0 0/1 * * * ?），
 * 天然单实例分片，无需 Redisson 分布式锁。本地 {@code @Scheduled} 仅作无 Admin 兜底。
 */
@Slf4j
@Service
public class GroupBuyNotifyJob {

    @Resource
    private ITradeTaskService tradeTaskService;

    @Value("${group.outbox.local-scheduler-enabled:false}")
    private boolean localSchedulerEnabled;

    @XxlJob("groupBuyNotifyJob")
    public void exec() {
        try {
            Map<String, Integer> result = tradeTaskService.execNotifyJob();
            log.info("定时任务，回调通知完成 result:{}", JsonUtils.toJson(result));
        } catch (Exception e) {
            log.error("定时任务，回调通知完成失败", e);
            throw new RuntimeException(e);
        }
    }

    @Scheduled(
            fixedDelayString = "${group.outbox.publish-interval-ms:60000}",
            initialDelayString = "${group.outbox.publish-initial-delay-ms:60000}")
    public void dispatchLocally() {
        if (localSchedulerEnabled) {
            exec();
        }
    }

}
