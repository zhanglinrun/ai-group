package com.aigroup.auth.job;

import com.aigroup.auth.service.AuthOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes committed Auth outbox rows. Prefer XXL-JOB; keep the local
 * {@code @Scheduled} path as an explicit fallback when Admin is unavailable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthOutboxPublishJob {

    private final AuthOutboxService authOutboxService;

    @Value("${auth.outbox.local-scheduler-enabled:false}")
    private boolean localSchedulerEnabled;

    @XxlJob("authOutboxPublishJob")
    public void exec() {
        authOutboxService.dispatchPending();
    }

    @Scheduled(fixedDelayString = "${spring.rabbitmq.outbox-dispatch-ms:1000}")
    public void dispatchLocally() {
        if (localSchedulerEnabled) {
            exec();
        }
    }
}
