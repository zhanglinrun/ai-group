package com.linrun.agent.trigger.job;

import com.linrun.agent.domain.agent.quota.QuotaSettlementRecoveryService;
import com.linrun.agent.types.agent.config.AgentExecutorNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Startup and periodic lease/CAS convergence of durable quota commands. */
@Slf4j
@Component
public class QuotaSettlementRecoveryJob implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Duration SCAN_INTERVAL = Duration.ofSeconds(15);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final int BATCH_LIMIT = 100;

    private final QuotaSettlementRecoveryService recoveryService;
    private final TaskScheduler scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduledFuture;

    public QuotaSettlementRecoveryJob(
            QuotaSettlementRecoveryService recoveryService,
            @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER) TaskScheduler scheduler) {
        this.recoveryService = recoveryService;
        this.scheduler = scheduler;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        recoverSafely();
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                this::recoverSafely,
                Instant.now().plus(SCAN_INTERVAL),
                SCAN_INTERVAL);
    }

    private void recoverSafely() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            int attempted = recoveryService.recoverDue(LEASE_DURATION, BATCH_LIMIT);
            if (attempted > 0) {
                log.info("durable quota recovery attempted count={}", attempted);
            }
        } catch (Exception failure) {
            log.error("durable quota recovery scan failed errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            scanning.set(false);
        }
    }

    @Override
    public void destroy() {
        ScheduledFuture<?> current = scheduledFuture;
        if (current != null) {
            current.cancel(false);
        }
    }
}
