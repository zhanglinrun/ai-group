package com.aigroup.groupbuy.trigger.job;

import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundCommandEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Fuzhengwei bugstack.cn @????
 * @description ??????????????
 * @create 2025-01-31 15:00
 */
@Slf4j
@Service
public class TimeoutRefundJob {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * ???????????????
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void exec() {
        // ??????????????
        RLock lock = redissonClient.getLock("group_buy_market_timeout_refund_job_exec");
        try {
            // waitTime????????????
            // leaseTime????????????
            boolean isLocked = lock.tryLock(3, 60, TimeUnit.SECONDS);
            if (!isLocked) {
                log.info("?????????????????????");
                return;
            }

            log.info("timeout refund job started");
            
            // ????????????
            List<UserGroupBuyOrderDetailEntity> timeoutOrderList = tradeRefundOrderService.queryTimeoutUnpaidOrderList();
            if (timeoutOrderList == null || timeoutOrderList.isEmpty()) {
                log.info("???????????????????");
                return;
            }

            log.info("?????????????????????{}", timeoutOrderList.size());
            
            int successCount = 0;
            int failCount = 0;
            
            // ??????????
            for (UserGroupBuyOrderDetailEntity orderDetail : timeoutOrderList) {
                try {
                    // ???????
                    TradeRefundCommandEntity refundCommand = TradeRefundCommandEntity.builder()
                            .userId(orderDetail.getUserId())
                            .outTradeNo(orderDetail.getOutTradeNo())
                            .source(orderDetail.getSource())
                            .channel(orderDetail.getChannel())
                            .build();
                    
                    // ?????
                    tradeRefundOrderService.refundOrder(refundCommand);
                    successCount++;
                    
                    log.info("???????????ID?{}??????{}", orderDetail.getUserId(), orderDetail.getOutTradeNo());
                    
                } catch (Exception e) {
                    failCount++;
                    log.error("???????????ID?{}??????{}??????{}", 
                            orderDetail.getUserId(), orderDetail.getOutTradeNo(), e.getMessage(), e);
                }
            }
            
            log.info("timeout refund job finished success={} fail={}", successCount, failCount);
            
        } catch (Exception e) {
            log.error("timeout refund job failed", e);
        } finally {
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}