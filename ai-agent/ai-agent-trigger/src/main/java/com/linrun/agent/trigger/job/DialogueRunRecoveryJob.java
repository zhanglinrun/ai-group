package com.linrun.agent.trigger.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.ledger.DialogueRunRecoveryService;
import com.linrun.agent.types.agent.config.AgentExecutorNames;
import com.linrun.agent.types.agent.config.AgentExecutorProperties;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Startup and periodic orphan-run reaper. It never resumes or takes over execution. */
@Slf4j
@Component
public class DialogueRunRecoveryJob implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final DialogueRunRecoveryService recoveryService;
    private final TaskScheduler scheduler;
    private final AgentExecutorProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduledFuture;

    public DialogueRunRecoveryJob(DialogueRunRecoveryService recoveryService,
                                  @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER) TaskScheduler scheduler,
                                  AgentExecutorProperties properties) {
        this.recoveryService = recoveryService;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        AgentExecutorProperties.RunRecovery config = properties.getRunRecovery();
        if (config == null || !Boolean.TRUE.equals(config.getEnabled()) || !started.compareAndSet(false, true)) {
            return;
        }
        recoverSafely(config);
        long intervalMillis = positive(config.getScanIntervalMillis(), 60_000L);
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                () -> recoverSafely(config),
                Instant.now().plusMillis(intervalMillis),
                Duration.ofMillis(intervalMillis));
    }

    private void recoverSafely(AgentExecutorProperties.RunRecovery config) {
        try {
            int recovered = recoveryService.failWorkerLostRuns(
                    LocalDateTime.now(),
                    Duration.ofMillis(nonNegative(config.getDeadlineGraceMillis(), 300_000L)),
                    Duration.ofMillis(positive(config.getHeartbeatTimeoutMillis(), 60_000L)),
                    config.getBatchLimit() == null ? 200 : config.getBatchLimit());
            if (recovered > 0) {
                log.warn("terminalized worker-lost Agent runs count={}", recovered);
            }
        } catch (Exception error) {
            log.error("worker-lost Agent run recovery scan failed errorType={}",
                    error.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        ScheduledFuture<?> current = scheduledFuture;
        if (current != null) {
            current.cancel(false);
        }
    }

    private long positive(Long value, long fallback) {
        return value == null || value <= 0L ? fallback : value;
    }

    private long nonNegative(Long value, long fallback) {
        return value == null || value < 0L ? fallback : value;
    }
}
