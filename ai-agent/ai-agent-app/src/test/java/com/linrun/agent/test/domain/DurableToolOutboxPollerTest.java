package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolControlPlane;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolExecutionRequest;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolWakeupPublisher;
import com.linrun.agent.domain.agent.runtime.tool.durable.InMemoryDurableToolStore;
import com.linrun.agent.infrastructure.tool.durable.DurableToolOutboxPoller;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/** Verifies the P160 lifecycle-managed fallback for missed durable-tool wake-ups. */
public class DurableToolOutboxPollerTest {

    @Test
    public void shouldScanAtStartupSchedulePeriodicFallbackAndCancelOnDestroy() {
        DurableToolControlPlane controlPlane = new DurableToolControlPlane(new InMemoryDurableToolStore());
        controlPlane.schedule(DurableToolExecutionRequest.builder()
                .toolInvocationId(101L)
                .runId(88L)
                .requestId("request-88")
                .toolCallId("call-101")
                .toolName("deep_search")
                .operationKey("sha256:outbox-poller")
                .inputJson("{\"query\":\"durable outbox\"}")
                .ownerWorkerId("agent-worker")
                .fencingToken(7L)
                .retryable(true)
                .build());
        DurableToolWakeupPublisher publisher = Mockito.mock(DurableToolWakeupPublisher.class);
        TaskScheduler scheduler = Mockito.mock(TaskScheduler.class);
        ScheduledFuture<?> scheduledFuture = Mockito.mock(ScheduledFuture.class);
        Mockito.doReturn(scheduledFuture).when(scheduler).scheduleWithFixedDelay(
                Mockito.any(Runnable.class), Mockito.any(Instant.class), Mockito.any(Duration.class));

        DurableToolOutboxPoller poller = new DurableToolOutboxPoller(controlPlane, publisher, scheduler, 5_000L);
        poller.onApplicationEvent(Mockito.mock(ApplicationReadyEvent.class));
        poller.onApplicationEvent(Mockito.mock(ApplicationReadyEvent.class));

        Mockito.verify(publisher).publish(Mockito.argThat(message -> Long.valueOf(101L).equals(message.getToolInvocationId())));
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        Mockito.verify(scheduler).scheduleWithFixedDelay(
                taskCaptor.capture(), Mockito.any(Instant.class), Mockito.eq(Duration.ofSeconds(5)));
        taskCaptor.getValue().run();
        Mockito.verifyNoMoreInteractions(publisher);

        poller.destroy();
        Mockito.verify(scheduledFuture).cancel(false);
    }
}
