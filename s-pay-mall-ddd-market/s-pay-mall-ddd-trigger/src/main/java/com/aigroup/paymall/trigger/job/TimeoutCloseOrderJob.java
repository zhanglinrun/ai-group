package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.job.support.AlipayOrderReconcileSupport;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * Closes orders unpaid for more than 30 minutes.
 * <p>
 * C1: before closing, each order is reconciled against alipay. If the buyer
 * paid right at the timeout boundary the order is recovered to PAY_SUCCESS
 * instead of being closed (money taken + order closed would otherwise be
 * unrecoverable). Only after alipay confirms the trade is not paid is the
 * alipay-side trade closed and then the local order closed.
 * 调度由 XXL-JOB admin 集中管理（cron: 0 5/30 * * * ?）。
 */
@Slf4j
@Component()
public class TimeoutCloseOrderJob {

    @Resource
    private IOrderService orderService;
    @Resource
    private AlipayOrderReconcileSupport alipayOrderReconcileSupport;

    @XxlJob("timeoutCloseOrderJob")
    public void exec() {
        try {
            List<String> orderIds = orderService.queryTimeoutCloseOrderList();
            if (null == orderIds || orderIds.isEmpty()) {
                return;
            }
            for (String orderId : orderIds) {
                try {
                    if (alipayOrderReconcileSupport.recoverIfPaidOnAlipay(orderId)) {
                        log.info("timeout close job - order paid on alipay, recovered instead of closed orderId:{}", orderId);
                        continue;
                    }
                    // close the alipay trade first; if the close is not confirmed the
                    // payment state is unknown, so keep the local order open for the
                    // next run instead of risking "paid but locally closed"
                    if (!alipayOrderReconcileSupport.closeAlipayTrade(orderId)) {
                        log.warn("timeout close job - alipay close unconfirmed, skip local close orderId:{}", orderId);
                        continue;
                    }
                    boolean status = orderService.changeOrderClose(orderId);
                    log.info("timeout close job - closed orderId:{} status:{}", orderId, status);
                } catch (Exception e) {
                    // reconcile failed: payment state unknown, never close blindly
                    log.error("timeout close job - reconcile failed, order kept open orderId:{}", orderId, e);
                }
            }
        } catch (Exception e) {
            log.error("timeout close job failed", e);
        }
    }

}
