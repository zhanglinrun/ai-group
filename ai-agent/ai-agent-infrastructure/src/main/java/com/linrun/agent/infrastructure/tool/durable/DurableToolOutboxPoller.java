package com.linrun.agent.infrastructure.tool.durable;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolControlPlane;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolOutboxMessage;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolWakeupPublisher;
import com.linrun.agent.types.agent.config.AgentExecutorNames;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
/** DB scan fallback for missed Kafka wake-ups. It never replays UNKNOWN worker side effects. */
@Slf4j
@Component
public class DurableToolOutboxPoller implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 5_000L;

    private final DurableToolControlPlane controlPlane;
    private final DurableToolWakeupPublisher wakeupPublisher;
    private final TaskScheduler scheduler;
    private final Duration pollInterval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduledFuture;

    public DurableToolOutboxPoller(DurableToolControlPlane controlPlane,
                                   DurableToolWakeupPublisher wakeupPublisher,
                                   @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER) TaskScheduler scheduler,
                                   @org.springframework.beans.factory.annotation.Value("${aigroup.durable-tool.outbox-poll-ms:5000}") long pollIntervalMillis) {
        this.controlPlane = controlPlane;
        this.wakeupPublisher = wakeupPublisher;
        this.scheduler = scheduler;
        this.pollInterval = Duration.ofMillis(positive(pollIntervalMillis));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scanSafely();
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                this::scanSafely,
                Instant.now().plus(pollInterval),
                pollInterval);
    }

    public void scanOnce() {
        for (DurableToolOutboxMessage message : controlPlane.dueOutbox(100)) {
            try {
                wakeupPublisher.publish(message);
                controlPlane.markOutboxPublished(message.getId());
            } catch (Exception publishError) {
                controlPlane.markOutboxRetry(message.getId());
                log.warn("durable tool outbox wake-up failed invocationId={} outboxId={} errorType={}",
                        message.getToolInvocationId(), message.getId(), publishError.getClass().getSimpleName());
            }
        }
        controlPlane.reconcile();
    }

    @Override
    public void destroy() {
        ScheduledFuture<?> current = scheduledFuture;
        if (current != null) {
            current.cancel(false);
        }
    }

    private void scanSafely() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            scanOnce();
        } catch (Exception error) {
            log.error("durable tool outbox scan failed errorType={}", error.getClass().getSimpleName());
        } finally {
            scanning.set(false);
        }
    }

    private long positive(long value) {
        return value > 0L ? value : DEFAULT_POLL_INTERVAL_MILLIS;
    }
}
