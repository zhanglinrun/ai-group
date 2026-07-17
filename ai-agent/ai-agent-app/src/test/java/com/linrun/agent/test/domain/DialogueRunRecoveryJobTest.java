package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.DialogueRunRecoveryService;
import com.linrun.agent.trigger.job.DialogueRunRecoveryJob;
import com.linrun.agent.types.agent.config.AgentExecutorProperties;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

public class DialogueRunRecoveryJobTest {

    @Test
    public void shouldScanAtStartupSchedulePeriodicRecoveryAndCancelOnDestroy() {
        DialogueRunRecoveryService recoveryService = Mockito.mock(DialogueRunRecoveryService.class);
        TaskScheduler scheduler = Mockito.mock(TaskScheduler.class);
        ScheduledFuture<?> scheduledFuture = Mockito.mock(ScheduledFuture.class);
        AgentExecutorProperties properties = new AgentExecutorProperties();
        properties.getRunRecovery().setScanIntervalMillis(5_000L);
        Mockito.doReturn(scheduledFuture).when(scheduler).scheduleWithFixedDelay(
                Mockito.any(Runnable.class), Mockito.any(Instant.class), Mockito.any(Duration.class));

        DialogueRunRecoveryJob job = new DialogueRunRecoveryJob(recoveryService, scheduler, properties);
        job.onApplicationEvent(Mockito.mock(ApplicationReadyEvent.class));

        Mockito.verify(recoveryService).failWorkerLostRuns(
                Mockito.any(), Mockito.eq(Duration.ofMinutes(5)), Mockito.eq(Duration.ofMinutes(1)), Mockito.eq(200));
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        Mockito.verify(scheduler).scheduleWithFixedDelay(
                taskCaptor.capture(), Mockito.any(Instant.class), Mockito.eq(Duration.ofSeconds(5)));
        taskCaptor.getValue().run();
        Mockito.verify(recoveryService, Mockito.times(2)).failWorkerLostRuns(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt());

        job.destroy();
        Mockito.verify(scheduledFuture).cancel(false);
    }

    @Test
    public void shouldNotStartRecoveryWhenDisabled() {
        DialogueRunRecoveryService recoveryService = Mockito.mock(DialogueRunRecoveryService.class);
        TaskScheduler scheduler = Mockito.mock(TaskScheduler.class);
        AgentExecutorProperties properties = new AgentExecutorProperties();
        properties.getRunRecovery().setEnabled(false);

        DialogueRunRecoveryJob job = new DialogueRunRecoveryJob(recoveryService, scheduler, properties);
        job.onApplicationEvent(Mockito.mock(ApplicationReadyEvent.class));
        job.destroy();

        Mockito.verifyNoInteractions(recoveryService, scheduler);
    }
}
