package com.aigroup.groupbuy.trigger.job;

import com.aigroup.groupbuy.domain.activity.service.IMarketConfigCacheReconcileService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Samples marketing-config cache vs MySQL every 5 minutes (XXL primary).
 */
@Slf4j
@Service
public class MarketConfigCacheReconcileJob {

    private final IMarketConfigCacheReconcileService reconcileService;

    @Value("${group.market-config.reconcile.local-scheduler-enabled:false}")
    private boolean localSchedulerEnabled;

    public MarketConfigCacheReconcileJob(IMarketConfigCacheReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @XxlJob("marketConfigCacheReconcileJob")
    public void exec() {
        int mismatches = reconcileService.reconcile();
        log.info("market config cache reconcile finished mismatches={}", mismatches);
    }

    @Scheduled(fixedDelayString = "${group.market-config.reconcile.interval-ms:300000}",
            initialDelayString = "${group.market-config.reconcile.initial-delay-ms:300000}")
    public void dispatchLocally() {
        if (localSchedulerEnabled) {
            exec();
        }
    }
}
