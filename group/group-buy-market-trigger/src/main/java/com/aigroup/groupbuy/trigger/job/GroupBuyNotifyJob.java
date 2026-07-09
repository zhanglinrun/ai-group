package com.aigroup.groupbuy.trigger.job;

import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeTaskService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author Fuzhengwei bugstack.cn @小傅�?
 * @description 拼团完结回调通知任务；拼团回调任务表，实际公司场景会定时清理数据结转，不会有太多数据挤压
 * @create 2025-01-31 10:27
 */
@Slf4j
@Service
public class GroupBuyNotifyJob {

    @Resource
    private ITradeTaskService tradeTaskService;

    @Resource
    private RedissonClient redissonClient;

    // B3: was "0 0 0 * * ?" (daily); minute-level so a failed settlement callback is
    // compensated within a minute instead of up to 24h. Concurrency is still guarded
    // by the Redisson tryLock below (non-blocking, released in finally).
    @Scheduled(cron = "0 0/1 * * * ?")
    public void exec() {
        // 为什么加锁？分布式应用N台机器部署互备（一个应用实例挂了，还有另外可用的），任务调度会有N个同时执行，那么这里需要增加抢占机制，谁抢占到谁就执行。完毕后，下一轮继续抢占�?
        RLock lock = redissonClient.getLock("group_buy_market_notify_job_exec");
        try {
            // waitTime：等待获取锁的最长时�?
            // leaseTime：租约时间，如果当前线程成功获取到锁，那么锁将被持有的时间长度。这个时间过后，锁会自动释放。续租时间可按照执行方法时间的耗时max来设置。如 50毫秒
            boolean isLocked = lock.tryLock(3, 0, TimeUnit.SECONDS);
            if (!isLocked) return;

            Map<String, Integer> result = tradeTaskService.execNotifyJob();
            log.info("定时任务，回调通知完成 result:{}", JSON.toJSONString(result));
        } catch (Exception e) {
            log.error("定时任务，回调通知完成失败", e);
        } finally {
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}
