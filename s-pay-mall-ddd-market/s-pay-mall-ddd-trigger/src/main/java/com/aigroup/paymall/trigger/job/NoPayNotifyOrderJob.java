package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.job.support.AlipayOrderReconcileSupport;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * Recovers orders whose pay callback was lost: queries alipay for PAY_WAIT
 * orders and advances the paid ones to PAY_SUCCESS. The alipay query/recover
 * logic is shared with TimeoutCloseOrderJob through
 * {@link AlipayOrderReconcileSupport} (C1).
 * 调度由 XXL-JOB admin 集中管理（cron: 0 0/30 * * * ?）。
 */
@Slf4j
@Component()
public class NoPayNotifyOrderJob {

    @Resource
    private IOrderService orderService;
    @Resource
    private AlipayOrderReconcileSupport alipayOrderReconcileSupport;

    @XxlJob("noPayNotifyOrderJob")
    public void exec() {
        try {
            List<String> orderIds = orderService.queryNoPayNotifyOrder();
            if (null == orderIds || orderIds.isEmpty()) return;

            for (String orderId : orderIds) {
                try {
                    boolean recovered = alipayOrderReconcileSupport.recoverIfPaidOnAlipay(orderId);
                    if (recovered) {
                        log.info("no-pay notify job - recovered paid order orderId:{}", orderId);
                    }
                } catch (Exception e) {
                    log.error("no-pay notify job - reconcile failed orderId:{}", orderId, e);
                }
            }
        } catch (Exception e) {
            log.error("no-pay notify job failed", e);
        }
    }

}
