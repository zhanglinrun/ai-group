package com.aigroup.groupbuy.trigger.job;

import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundCommandEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 拼团超时退款任务。调度由 XXL-JOB admin 中心集中管理（cron 每分钟执行），
 * 天然单实例分片，无需 Redisson 分布式锁。
 */
@Slf4j
@Service
public class TimeoutRefundJob {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    @Resource
    private ITradeSettlementOrderService tradeSettlementOrderService;

    @XxlJob("timeoutRefundJob")
    public void exec() {
        try {
            log.info("timeout refund job started");

            // 阶梯拼团：先结算"到期已达最低档"的团（按最终人数定档发放），使其转为成团；
            // 之后退款扫描只会命中仍不足最低档(团不成立)的团，实现"≥门槛按档发、<门槛退款"。
            int settledTeams = tradeSettlementOrderService.settleExpiredFormedTeams();
            log.info("timeout tiered settle expired formed teams: {}", settledTeams);

            // 未支付超时（unpaid_unlock）：释放锁单库存
            int[] unpaid = refundBatch(tradeRefundOrderService.queryTimeoutUnpaidOrderList(), "unpaid");
            // 已支付但拼团超时未成团（paid_unformed）：自动退款闭环，避免付了钱却永不退款
            int[] paidUnformed = refundBatch(tradeRefundOrderService.queryTimeoutPaidUnformedOrderList(), "paid_unformed");

            log.info("timeout refund job finished unpaid[success={} fail={}] paidUnformed[success={} fail={}]",
                    unpaid[0], unpaid[1], paidUnformed[0], paidUnformed[1]);

        } catch (Exception e) {
            log.error("timeout refund job failed", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 对一批超时订单执行退款（走统一的 refundOrder 责任链，按订单/团状态自动分派退款类型）。
     * @return [successCount, failCount]
     */
    private int[] refundBatch(List<UserGroupBuyOrderDetailEntity> orderList, String scene) {
        if (orderList == null || orderList.isEmpty()) {
            return new int[]{0, 0};
        }
        log.info("timeout refund scan {} size={}", scene, orderList.size());
        int successCount = 0;
        int failCount = 0;
        for (UserGroupBuyOrderDetailEntity orderDetail : orderList) {
            try {
                TradeRefundCommandEntity refundCommand = TradeRefundCommandEntity.builder()
                        .userId(orderDetail.getUserId())
                        .outTradeNo(orderDetail.getOutTradeNo())
                        .source(orderDetail.getSource())
                        .channel(orderDetail.getChannel())
                        .build();
                tradeRefundOrderService.refundOrder(refundCommand);
                successCount++;
                log.info("timeout refund ok scene:{} userId:{} outTradeNo:{}", scene, orderDetail.getUserId(), orderDetail.getOutTradeNo());
            } catch (Exception e) {
                failCount++;
                log.error("timeout refund failed scene:{} userId:{} outTradeNo:{} err:{}",
                        scene, orderDetail.getUserId(), orderDetail.getOutTradeNo(), e.getMessage(), e);
            }
        }
        return new int[]{successCount, failCount};
    }

}
