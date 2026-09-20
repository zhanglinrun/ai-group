package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.benefit.service.IBenefitReconciliationService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BenefitReconciliationJob {
    private final IBenefitReconciliationService reconciliation;

    @Value("${pay.benefit-reconciliation.local-scheduler-enabled:false}")
    private boolean localSchedulerEnabled;

    public BenefitReconciliationJob(IBenefitReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @XxlJob("benefitReconciliationJob")
    public void exec() {
        int count = reconciliation.reconcilePublishedGrants();
        if (count > 0) {
            log.info("reconciled {} published benefit events", count);
        }
    }

    @Scheduled(fixedDelayString = "${pay.benefit-reconciliation.interval-ms:60000}",
               initialDelayString = "${pay.benefit-reconciliation.initial-delay-ms:60000}")
    public void dispatchLocally() {
        if (localSchedulerEnabled) {
            exec();
        }
    }
}
