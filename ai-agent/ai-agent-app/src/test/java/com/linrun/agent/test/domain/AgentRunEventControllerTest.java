package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerReadRepository;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunCancelResult;
import com.linrun.agent.domain.agent.service.session.SessionOwnershipDeniedException;
import com.linrun.agent.trigger.http.agent.AgentRunEventController;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** P30 HTTP boundary coverage: owner checks, cursor gaps, and database-tail handoff. */
public class AgentRunEventControllerTest {

    @After
    public void tearDown() {
        OwnerRequestContext.clear();
    }

    @Test
    public void shouldRecordOwnerScopedCancellationAndExplainExternalEffectsAreNotRolledBack() {
        OwnerRequestContext.bind(1001L);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.requestRunCancellation(701L, "1001", null)).thenReturn(
                new DialogueRunCancelResult(DialogueRunCancelResult.Status.ACCEPTED, 701L, "request-701"));
        AgentRunEventController controller = new AgentRunEventController(
                Mockito.mock(IExecutionLedgerReadRepository.class), new RecordingStore(), recorder);

        ResponseEntity<AgentRunEventController.CancelRunResponse> response = controller.cancel(701L);

        Assert.assertEquals(202, response.getStatusCode().value());
        Assert.assertTrue(response.getBody().accepted());
        Assert.assertTrue(response.getBody().message().contains("not rolled back"));
        Mockito.verify(recorder).requestRunCancellation(701L, "1001", null);
    }

    @Test(expected = SessionOwnershipDeniedException.class)
    public void shouldRejectCancellationWhenRecorderReportsOwnerMismatch() {
        OwnerRequestContext.bind(1001L);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.requestRunCancellation(702L, "1001", null)).thenReturn(
                new DialogueRunCancelResult(DialogueRunCancelResult.Status.OWNER_MISMATCH, 702L, "request-702"));
        AgentRunEventController controller = new AgentRunEventController(
                Mockito.mock(IExecutionLedgerReadRepository.class), new RecordingStore(), recorder);

        controller.cancel(702L);
    }

    @Test
    public void shouldCancelByRequestIdWithoutExposingNumericLedgerIdToBrowser() {
        OwnerRequestContext.bind(1001L);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.queryRunByRequestId("request-703")).thenReturn(run(703L, "request-703"));
        Mockito.when(recorder.requestRunCancellation(703L, "1001", null)).thenReturn(
                new DialogueRunCancelResult(DialogueRunCancelResult.Status.ACCEPTED, 703L, "request-703"));
        AgentRunEventController controller = new AgentRunEventController(readRepository, new RecordingStore(), recorder);

        ResponseEntity<AgentRunEventController.CancelRunResponse> response = controller.cancelByRequest("request-703");

        Assert.assertEquals(202, response.getStatusCode().value());
        Assert.assertTrue(response.getBody().accepted());
        Mockito.verify(recorder).requestRunCancellation(703L, "1001", null);
    }

    @Test
    public void shouldEmitGapWithoutReadingEventsWhenExplicitCursorPrecedesRetention() throws Exception {
        OwnerRequestContext.bind(1001L);
        RecordingStore store = new RecordingStore();
        store.add(4L, "text", "{\"type\":\"text\",\"runId\":\"request-703\",\"delta\":\"retained\"}");
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.queryRunById(703L)).thenReturn(run(703L, "request-703"));
        AgentRunEventController controller = new AgentRunEventController(
                readRepository, store, Mockito.mock(AgentExecutionRecorder.class));

        controller.replayAfterRunId(703L, "1", null);

        Assert.assertEquals("a gap must not replay a partial event suffix", 0, store.findAfterCalls.get());
    }

    @Test
    public void shouldTailEventsWrittenAfterInitialDatabaseCatchUpWatermark() throws Exception {
        OwnerRequestContext.bind(1001L);
        RecordingStore store = new RecordingStore();
        store.add(1L, "text", "{\"type\":\"text\",\"runId\":\"request-704\",\"delta\":\"first\"}");
        IExecutionLedgerReadRepository readRepository = Mockito.mock(IExecutionLedgerReadRepository.class);
        Mockito.when(readRepository.queryRunById(704L)).thenReturn(run(704L, "request-704"));
        AgentRunEventController controller = new AgentRunEventController(
                readRepository, store, Mockito.mock(AgentExecutionRecorder.class));

        controller.replayAfterRunId(704L, null, null);
        store.add(2L, "complete", "{\"type\":\"complete\",\"runId\":\"request-704\",\"summary\":\"done\",\"totalDurationMillis\":1,\"microcreditsConsumed\":0}");

        Assert.assertTrue("the live tail must read a terminal event inserted after the initial watermark",
                store.terminalRead.await(3, TimeUnit.SECONDS));
    }

    private DialogueRun run(Long id, String requestId) {
        return DialogueRun.builder().id(id).requestId(requestId).ownerId("1001").build();
    }

    private static final class RecordingStore implements AgentStreamEventStore {
        private final List<StoredStreamEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger findAfterCalls = new AtomicInteger();
        private final CountDownLatch terminalRead = new CountDownLatch(1);

        void add(long sequence, String eventType, String json) {
            events.add(new StoredStreamEvent(sequence, eventType, json));
        }

        @Override
        public void append(String requestId, String eventType, String eventJson) {
            throw new UnsupportedOperationException("not required by this controller test");
        }

        @Override
        public List<StoredStreamEvent> findByRequestId(String requestId) {
            return List.copyOf(events);
        }

        @Override
        public List<StoredStreamEvent> findByRequestIdAfter(String requestId, long afterSequence) {
            findAfterCalls.incrementAndGet();
            List<StoredStreamEvent> result = events.stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .toList();
            if (result.stream().anyMatch(event -> "complete".equals(event.eventType()))) {
                terminalRead.countDown();
            }
            return result;
        }
    }
}
