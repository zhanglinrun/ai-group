package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.order.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * WAIT_REFUND 补偿：已支付拼团单申请退款后挂入 WAIT_REFUND，等待 group 的 team_refund
 * MQ 回调触发支付宝退款。若该回调丢失，订单会永久卡在 WAIT_REFUND——钱已扣却永不退。
 * <p>
 * 本任务分钟级扫描滞留超阈值(见 queryWaitRefundTimeoutOrderList，5 分钟)的 WAIT_REFUND 单，
 * 重发拼团退单通知并直接走支付宝退款兜底（均幂等，与 team_refund 回调并发也安全）。
 * 调度由 XXL-JOB admin 集中管理（cron: 0 0/1 * * * ?）。
 */
@Slf4j
@Component
public class WaitRefundCompensateJob {

    @Resource
    private IOrderService orderService;

    @XxlJob("waitRefundCompensateJob")
    public void exec() {
        try {
            int count = orderService.compensateWaitRefund();
            if (count > 0) {
                log.info("wait-refund compensate job refunded {} stuck orders", count);
            }
        } catch (Exception e) {
            log.error("wait-refund compensate job failed", e);
        }
    }

}
