package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Independently publishes committed payment outbox rows. Business transactions
 * only insert rows; this poller plus consumer idempotency provides at-least-once
 * delivery without sending MQ messages before the database commit succeeds.
 * 调度由 XXL-JOB admin 集中管理（fixed-rate 1s），天然单实例分片。
 */
@Slf4j
@Component
public class OutboxEventPublishJob {

    @Resource
    private IBenefitEventService benefitEventService;

    /**
     * The full local Compose profile does not ship an XXL-JOB admin. Keep the
     * production XXL-JOB handler, but allow a single service instance to poll
     * its own committed outbox when that profile explicitly enables it.
     */
    @Value("${pay.outbox.local-scheduler-enabled:false}")
    private boolean localSchedulerEnabled;

    @XxlJob("outboxEventPublishJob")
    public void exec() {
        int count = benefitEventService.publishPendingEvents();
        if (count > 0) {
            log.info("outbox event publisher sent {} events", count);
        }
    }

    @Scheduled(
            fixedDelayString = "${pay.outbox.publish-interval-ms:1000}",
            initialDelayString = "${pay.outbox.publish-initial-delay-ms:1000}")
    public void dispatchLocally() {
        if (localSchedulerEnabled) {
            exec();
        }
    }
}
