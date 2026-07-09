package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.order.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * C2 settlement compensation: without this job, a paid group-buy order whose
 * settlement HTTP call to the group service failed (timeout, group down) stays
 * in PAY_SUCCESS forever - money taken, team never formed. Scans minute-level
 * for group-buy orders stuck in PAY_SUCCESS beyond the threshold (see
 * queryPaySuccessMarketTimeoutOrderList, 2 minutes) and re-sends the group
 * settlement, which is idempotent on the group side.
 * <p>
 * Scheduling/locking style follows BenefitEventRepublishJob: single-instance
 * @Scheduled; the scan is capped (LIMIT 20) and the downstream settlement is
 * idempotent, so an overlapping run is harmless.
 */
@Slf4j
@Component
public class MarketSettlementCompensateJob {

    @Resource
    private IOrderService orderService;

    @Scheduled(cron = "0 0/1 * * * ?")
    public void exec() {
        try {
            int count = orderService.compensateMarketSettlement();
            if (count > 0) {
                log.info("market settlement compensate job resent settlement for {} orders", count);
            }
        } catch (Exception e) {
            log.error("market settlement compensate job failed", e);
        }
    }

}
