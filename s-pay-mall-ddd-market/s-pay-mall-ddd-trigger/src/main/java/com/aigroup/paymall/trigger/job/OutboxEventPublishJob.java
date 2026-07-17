package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * Independently publishes committed payment outbox rows. Business transactions
 * only insert rows; this poller plus consumer idempotency provides at-least-once
 * delivery without sending MQ messages before the database commit succeeds.
 */
@Slf4j
@Component
public class OutboxEventPublishJob {

    @Resource
    private IBenefitEventService benefitEventService;

    @Scheduled(
            fixedDelayString = "${ai-group.pay.outbox.publish-delay-ms:1000}",
            initialDelayString = "${ai-group.pay.outbox.publish-initial-delay-ms:1000}")
    public void exec() {
        try {
            int count = benefitEventService.publishPendingEvents();
            if (count > 0) {
                log.info("outbox event publisher sent {} events", count);
            }
        } catch (Exception e) {
            log.error("outbox event publisher failed", e);
        }
    }
}
