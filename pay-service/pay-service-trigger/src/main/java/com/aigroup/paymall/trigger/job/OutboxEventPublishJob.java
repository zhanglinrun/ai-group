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
 * Prefer XXL-JOB admin scheduling ({@code outboxEventPublishJob}); enable
 * {@code pay.outbox.local-scheduler-enabled} only as a no-Admin fallback.
 */
@Slf4j
@Component
public class OutboxEventPublishJob {

    @Resource
    private IBenefitEventService benefitEventService;

    /**
     * Prefer XXL-JOB for production and full Compose. Keep this Spring
     * {@code @Scheduled} path as an explicit fallback when Admin is unavailable.
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
