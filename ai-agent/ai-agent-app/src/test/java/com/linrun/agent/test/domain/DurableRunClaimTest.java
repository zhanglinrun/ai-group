package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.DialogueRunRecoveryService;
import com.linrun.agent.domain.agent.ledger.DialogueRunRequestFingerprint;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerWriteRepository;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.impl.AgentExecutionRecorderImpl;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ExecutionRunDetail;
import com.linrun.agent.domain.agent.ledger.replay.DialogueRunReplayService;
import com.linrun.agent.domain.agent.ledger.replay.ReplayProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.AgentRuntime;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.printer.ReplayFrameSink;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.ledger.tooloutput.ToolOutputWriter;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.scheduling.TaskScheduler;

/** Regression coverage for durable request-id claiming and terminal replay. */
public class DurableRunClaimTest {

    @Test
    public void shouldGrantExactlyOneNewClaimUnderConcurrentDuplicateRequests() throws Exception {
        ExecutionLedgerFixtureFactory.LedgerTestContext context =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        DialogueRunStartRecord record = startRecord("req-claim-concurrent", "session-claim", "1001");
        int contenders = 8;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DialogueRunClaim>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < contenders; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    Assert.assertTrue(start.await(5, TimeUnit.SECONDS));
                    return context.recorder.claimRun(record);
                }));
            }
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<DialogueRunClaim> claims = new ArrayList<>();
            for (Future<DialogueRunClaim> future : futures) {
                claims.add(future.get(10, TimeUnit.SECONDS));
            }
            Assert.assertEquals(1L, claims.stream()
                    .filter(claim -> claim.getDisposition() == DialogueRunClaim.Disposition.NEW)
                    .count());
            Assert.assertEquals(contenders - 1L, claims.stream()
                    .filter(claim -> claim.getDisposition() == DialogueRunClaim.Disposition.RUNNING)
                    .count());
            Assert.assertEquals(1, context.queryService.querySessionRuns("session-claim").size());
            Assert.assertTrue(context.queryService.queryRunDetail("req-claim-concurrent")
                    .getLlmInvocations().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldClassifyFinishedAndRejectOwnerOrSessionReuse() {
        ExecutionLedgerFixtureFactory.LedgerTestContext context =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        DialogueRunStartRecord original = startRecord("req-claim-finished", "session-a", "1001");
        DialogueRunClaim first = context.recorder.claimRun(original);
        context.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(first.getRunId())
                .requestId(original.getRequestId())
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("durable result")
                .build());

        DialogueRunClaim finished = context.recorder.claimRun(original);
        Assert.assertEquals(DialogueRunClaim.Disposition.FINISHED, finished.getDisposition());
        Assert.assertEquals("durable result", finished.getFinalSummaryText());

        DialogueRunStartRecord legacyCompatible = startRecord(
                original.getRequestId(), original.getSessionId(), original.getOwnerId());
        legacyCompatible.setRequestFingerprint("new-runtime-fingerprint");
        Assert.assertEquals(DialogueRunClaim.Disposition.FINISHED,
                context.recorder.claimRun(legacyCompatible).getDisposition());

        DialogueRunClaim ownerMismatch = context.recorder.claimRun(
                startRecord(original.getRequestId(), "session-a", "2002"));
        Assert.assertEquals(DialogueRunClaim.Disposition.OWNER_MISMATCH, ownerMismatch.getDisposition());

        DialogueRunClaim sessionMismatch = context.recorder.claimRun(
                startRecord(original.getRequestId(), "session-b", "1001"));
        Assert.assertEquals(DialogueRunClaim.Disposition.REQUEST_MISMATCH, sessionMismatch.getDisposition());

        try {
            context.recorder.createRun(original);
            Assert.fail("legacy createRun must fail closed on a unique-key conflict");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("already claimed"));
        }
    }

    @Test
    public void shouldRetryCriticalRunFinalizationBeforeReturning() {
        IExecutionLedgerWriteRepository repository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        DialogueRun existing = DialogueRun.builder()
                .id(501L)
                .runUid("req-finish-retry")
                .requestId("req-finish-retry")
                .sessionId("session-finish-retry")
                .ownerId("1001")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .startedAt(java.time.LocalDateTime.now().minusSeconds(1))
                .build();
        Mockito.when(repository.queryRunByRequestId("req-finish-retry")).thenReturn(existing);
        Mockito.when(repository.queryLlmInvocationsByRunId(501L)).thenReturn(List.of());
        Mockito.when(repository.queryToolInvocationsByRunId(501L)).thenReturn(List.of());
        Mockito.when(repository.queryArtifactsByRunId(501L)).thenReturn(List.of());
        Mockito.when(repository.updateRunFinish(Mockito.any()))
                .thenThrow(new IllegalStateException("transient-1"))
                .thenThrow(new IllegalStateException("transient-2"))
                .thenReturn(1);
        Mockito.when(repository.queryRunsBySessionId("session-finish-retry")).thenReturn(List.of());
        AgentExecutionRecorder recorder = new AgentExecutionRecorderImpl(
                repository, Mockito.mock(ToolOutputWriter.class));

        recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(501L)
                .requestId("req-finish-retry")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("durable")
                .build());

        Mockito.verify(repository, Mockito.times(3)).updateRunFinish(Mockito.any());
    }

    @Test
    public void shouldPropagatePermanentRunFinalizationFailure() {
        IExecutionLedgerWriteRepository repository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        DialogueRun existing = DialogueRun.builder()
                .id(502L)
                .runUid("req-finish-failed")
                .requestId("req-finish-failed")
                .sessionId("session-finish-failed")
                .ownerId("1001")
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .startedAt(java.time.LocalDateTime.now().minusSeconds(1))
                .build();
        Mockito.when(repository.queryRunByRequestId("req-finish-failed")).thenReturn(existing);
        Mockito.when(repository.queryLlmInvocationsByRunId(502L)).thenReturn(List.of());
        Mockito.when(repository.queryToolInvocationsByRunId(502L)).thenReturn(List.of());
        Mockito.when(repository.queryArtifactsByRunId(502L)).thenReturn(List.of());
        Mockito.when(repository.updateRunFinish(Mockito.any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        AgentExecutionRecorder recorder = new AgentExecutionRecorderImpl(
                repository, Mockito.mock(ToolOutputWriter.class));

        try {
            recorder.finishRun(DialogueRunFinishRecord.builder()
                    .runId(502L)
                    .requestId("req-finish-failed")
                    .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                    .finalSummaryText("must not be acknowledged")
                    .build());
            Assert.fail("permanent terminal persistence failure must propagate");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("failed to persist terminal dialogue run"));
        }
        Mockito.verify(repository, Mockito.times(3)).updateRunFinish(Mockito.any());
    }

    @Test
    public void shouldRejectRunningDuplicateBeforeToolOrModelAssembly() {
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(DialogueRunClaim.builder()
                .disposition(DialogueRunClaim.Disposition.RUNNING)
                .runId(91L)
                .runUid("req-running-duplicate")
                .requestId("req-running-duplicate")
                .ownerId("1001")
                .sessionId("session-running")
                .runStatus(ExecutionLedgerConstants.STATUS_RUNNING)
                .build());

        AgentRuntime runtime = new AgentRuntime(
                toolFactory, recorder, Mockito.mock(ReactorRuntimeDependencies.class), loopFactory);
        String result = runtime.run(request("req-running-duplicate", "session-running", "1001"), printer);

        Assert.assertEquals("", result);
        Mockito.verifyNoInteractions(toolFactory, loopFactory);
        Mockito.verify(recorder, Mockito.never()).finishRun(Mockito.any());
        Mockito.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && "RUN_ALREADY_IN_PROGRESS".equals(failure.code())));
    }

    @Test
    public void shouldReplayFinishedRunWithCanonicalTerminalOrderWithoutExecution() {
        String requestId = "req-finished-replay";
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        ExecutionLedgerQueryService queryService = Mockito.mock(ExecutionLedgerQueryService.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(DialogueRunClaim.builder()
                .disposition(DialogueRunClaim.Disposition.FINISHED)
                .runId(92L)
                .runUid(requestId)
                .requestId(requestId)
                .ownerId("1001")
                .sessionId("session-finished")
                .runStatus(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("replayed answer")
                .build());
        DialogueRunView run = DialogueRunView.builder()
                .id(92L)
                .runUid(requestId)
                .requestId(requestId)
                .ownerId("1001")
                .sessionId("session-finished")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("replayed answer")
                .build();
        Mockito.when(queryService.queryRunDetail(requestId)).thenReturn(ExecutionRunDetail.builder()
                .run(run)
                .llmInvocations(List.of())
                .toolInvocations(List.of())
                .artifacts(List.of())
                .build());
        DefaultToolInvocationProjector defaultProjector = new DefaultToolInvocationProjector();
        ReplayProjector replayProjector = new ReplayProjector(
                new ToolInvocationProjectorRegistry(List.of(), defaultProjector));
        Printer printer = Mockito.mock(
                Printer.class, Mockito.withSettings().extraInterfaces(ReplayFrameSink.class));
        List<GptProcessResult> frames = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
                    frames.add(invocation.getArgument(0));
                    return null;
                })
                .when((ReplayFrameSink) printer)
                .sendReplayFrame(Mockito.any());

        AgentRuntime runtime = new AgentRuntime(
                toolFactory,
                recorder,
                Mockito.mock(ReactorRuntimeDependencies.class),
                loopFactory,
                null,
                new DialogueRunReplayService(queryService, replayProjector)
        );
        String result = runtime.run(request(requestId, "session-finished", "1001"), printer);

        Assert.assertEquals("replayed answer", result);
        Mockito.verifyNoInteractions(toolFactory, loopFactory);
        Assert.assertTrue(frames.size() >= 3);
        Assert.assertEquals("run_finished", messageType(frames.get(frames.size() - 2)));
        Assert.assertEquals("result", messageType(frames.get(frames.size() - 1)));
        for (int index = 0; index < frames.size() - 1; index++) {
            Assert.assertFalse(frames.get(index).isFinished());
        }
        Assert.assertTrue(frames.get(frames.size() - 1).isFinished());
    }

    @Test
    public void shouldRejectSameRequestIdWhenClientPayloadFingerprintChanges() {
        ExecutionLedgerFixtureFactory.LedgerTestContext context =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        DialogueRunStartRecord original = startRecord("req-fingerprint", "session-fingerprint", "1001");
        original.setRequestFingerprint("fingerprint-a");
        DialogueRunClaim claim = context.recorder.claimRun(original);
        context.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(claim.getRunId())
                .requestId(original.getRequestId())
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("answer-a")
                .build());

        DialogueRunStartRecord changed = startRecord("req-fingerprint", "session-fingerprint", "1001");
        changed.setRequestFingerprint("fingerprint-b");
        DialogueRunClaim rejected = context.recorder.claimRun(changed);

        Assert.assertEquals(DialogueRunClaim.Disposition.REQUEST_MISMATCH, rejected.getDisposition());
        Assert.assertEquals("answer-a", rejected.getFinalSummaryText());
    }

    @Test
    public void shouldBuildStableFingerprintFromAllClientPayloadDimensions() {
        FileInformation first = file("knowledge.pdf", "resource-1", "https://signed/a");
        FileInformation second = file("diagram.png", "resource-2", "https://signed/b");
        AgentRequest baselineRequest = fingerprintRequest(
                "compare products", "DEEP", "docs", "model-a", true, "role-a", List.of(first, second));
        String baseline = DialogueRunRequestFingerprint.from(baselineRequest);

        FileInformation firstWithNewSignedUrl = file("knowledge.pdf", "resource-1", "https://signed/renewed");
        Assert.assertEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "DEEP", "docs", "model-a", true, "role-a",
                List.of(second, firstWithNewSignedUrl))));
        AgentRequest runtimeDecorated = fingerprintRequest(
                "compare products", "DEEP", "docs", "model-a", true, "role-a", List.of(first, second));
        runtimeDecorated.setQuery("compare products\n[runtime output-style instruction]");
        Assert.assertEquals("runtime prompt decoration must not change the client-payload identity",
                baseline, DialogueRunRequestFingerprint.from(runtimeDecorated));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare other products", "DEEP", "docs", "model-a", true, "role-a", List.of(first, second))));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "STANDARD", "docs", "model-a", true, "role-a", List.of(first, second))));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "DEEP", "html", "model-a", true, "role-a", List.of(first, second))));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "DEEP", "docs", "model-b", true, "role-a", List.of(first, second))));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "DEEP", "docs", "model-a", false, "role-a", List.of(first, second))));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "DEEP", "docs", "model-a", true, "role-b", List.of(first, second))));
        Assert.assertNotEquals(baseline, DialogueRunRequestFingerprint.from(fingerprintRequest(
                "compare products", "DEEP", "docs", "model-a", true, "role-a",
                List.of(first, file("diagram.png", "resource-3", "https://signed/b")))));
    }

    @Test
    public void shouldTerminalizeOnlyRunsWithExpiredDeadlineAndHeartbeat() {
        ExecutionLedgerFixtureFactory.LedgerTestContext context =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        LocalDateTime now = LocalDateTime.now();
        DialogueRunClaim workerLost = context.recorder.claimRun(timedStart(
                "req-worker-lost", now.minusMinutes(20), now.minusMinutes(6), now.minusMinutes(3)));
        DialogueRunClaim heartbeatFresh = context.recorder.claimRun(timedStart(
                "req-heartbeat-fresh", now.minusMinutes(20), now.minusMinutes(6), now.minusMinutes(3)));
        Assert.assertTrue(context.recorder.heartbeatRun(
                heartbeatFresh.getRunId(), heartbeatFresh.getRequestId(), now.minusSeconds(10)));
        DialogueRunClaim deadlineFresh = context.recorder.claimRun(timedStart(
                "req-deadline-fresh", now.minusMinutes(2), now.plusMinutes(1), now.minusMinutes(3)));
        DialogueRunClaim legacyWithoutDeadline = context.recorder.claimRun(
                startRecord("req-legacy-running", "session-recovery", "1001"));

        DialogueRunRecoveryService recoveryService = new DialogueRunRecoveryService(context.writeRepository);
        int recovered = recoveryService.failWorkerLostRuns(
                now, Duration.ofMinutes(5), Duration.ofMinutes(1), 100);

        Assert.assertEquals(1, recovered);
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED),
                context.writeRepository.queryRunByRequestId(workerLost.getRequestId()).getStatus());
        Assert.assertEquals(DialogueRunRecoveryService.WORKER_LOST_ERROR_CODE,
                context.writeRepository.queryRunByRequestId(workerLost.getRequestId()).getErrorCode());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_RUNNING),
                context.writeRepository.queryRunByRequestId(heartbeatFresh.getRequestId()).getStatus());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_RUNNING),
                context.writeRepository.queryRunByRequestId(deadlineFresh.getRequestId()).getStatus());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_RUNNING),
                context.writeRepository.queryRunByRequestId(legacyWithoutDeadline.getRequestId()).getStatus());
    }

    @Test
    public void shouldSynchronizeSessionHeadWhenLatestWorkerIsLost() {
        ExecutionLedgerFixtureFactory.LedgerTestContext context =
                ExecutionLedgerFixtureFactory.newLedgerTestContext();
        LocalDateTime now = LocalDateTime.now();
        DialogueRunStartRecord start = startRecord(
                "req-session-worker-lost", "session-worker-lost", "1001");
        start.setStartedAt(now.minusMinutes(20));
        start.setDeadlineAt(now.minusMinutes(6));
        start.setHeartbeatAt(now.minusMinutes(3));
        context.recorder.claimRun(start);

        DialogueRunRecoveryService recoveryService = new DialogueRunRecoveryService(context.writeRepository);
        Assert.assertEquals(1, recoveryService.failWorkerLostRuns(
                now, Duration.ofMinutes(5), Duration.ofMinutes(1), 100));

        DialogueSessionView session = context.queryService.querySession("1001", "session-worker-lost");
        Assert.assertNotNull(session);
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED), session.getStatus());
        Assert.assertEquals("req-session-worker-lost", session.getLatestRequestId());
        Assert.assertEquals(Integer.valueOf(1), session.getRunCount());
        Assert.assertEquals(Integer.valueOf(0), session.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(1), session.getFailedRunCount());
    }

    @Test
    public void shouldCancelRunHeartbeatWhenExecutionReturns() {
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        TaskScheduler scheduler = Mockito.mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeatFuture = Mockito.mock(ScheduledFuture.class);
        Mockito.doReturn(heartbeatFuture).when(scheduler).scheduleAtFixedRate(
                Mockito.any(Runnable.class), Mockito.any(Instant.class), Mockito.any(Duration.class));
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(DialogueRunClaim.builder()
                .disposition(DialogueRunClaim.Disposition.NEW)
                .runId(901L)
                .runUid("req-heartbeat-cancel")
                .requestId("req-heartbeat-cancel")
                .ownerId("1001")
                .sessionId("session-heartbeat-cancel")
                .runStatus(ExecutionLedgerConstants.STATUS_RUNNING)
                .build());
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenReturn(new ToolCollection());
        ReactorConfig reactorConfig = Mockito.mock(ReactorConfig.class);
        Mockito.when(reactorConfig.getAgentLoopModelName()).thenReturn("test-model");
        Mockito.when(reactorConfig.getLlmSettingsMap()).thenReturn(Map.of(
                "test-model", LLMSettings.builder().model("test-model").build()));
        ReactorRuntimeDependencies dependencies = ReactorRuntimeDependencies.builder()
                .reactorConfig(reactorConfig)
                .heartbeatScheduler(scheduler)
                .runHeartbeatIntervalMillis(10_000L)
                .build();

        AgentRuntime runtime = new AgentRuntime(toolFactory, recorder, dependencies, loopFactory);
        runtime.run(AgentRequest.builder()
                .requestId("req-heartbeat-cancel")
                .sessionId("session-heartbeat-cancel")
                .ownerId("1001")
                .query("必须且只能调用 MCP 工具 utility_estimate_llm_quota，禁止使用任何替代工具")
                .originalQuery("必须且只能调用 MCP 工具 utility_estimate_llm_quota，禁止使用任何替代工具")
                .online(true)
                .executionMode("STANDARD")
                .build(), printer);

        Mockito.verify(heartbeatFuture).cancel(false);
        Mockito.verifyNoInteractions(loopFactory);
    }

    @Test
    public void shouldRejectFingerprintMismatchBeforeReplayOrExecution() {
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(DialogueRunClaim.builder()
                .disposition(DialogueRunClaim.Disposition.REQUEST_MISMATCH)
                .runId(902L)
                .runUid("req-payload-mismatch")
                .requestId("req-payload-mismatch")
                .ownerId("1001")
                .sessionId("session-payload-mismatch")
                .runStatus(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("must-not-be-replayed")
                .build());

        AgentRuntime runtime = new AgentRuntime(
                toolFactory, recorder, Mockito.mock(ReactorRuntimeDependencies.class), loopFactory);
        String answer = runtime.run(request("req-payload-mismatch", "session-payload-mismatch", "1001"), printer);

        Assert.assertEquals("", answer);
        Mockito.verifyNoInteractions(toolFactory, loopFactory);
        Mockito.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && "RUN_REQUEST_MISMATCH".equals(failure.code())));
    }

    private DialogueRunStartRecord startRecord(String requestId, String sessionId, String ownerId) {
        return DialogueRunStartRecord.builder()
                .runUid(requestId)
                .requestId(requestId)
                .sessionId(sessionId)
                .ownerId(ownerId)
                .entryAgent(ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD)
                .queryText("claim test")
                .build();
    }

    private DialogueRunStartRecord timedStart(String requestId,
                                              LocalDateTime startedAt,
                                              LocalDateTime deadlineAt,
                                              LocalDateTime heartbeatAt) {
        DialogueRunStartRecord record = startRecord(requestId, "session-recovery", "1001");
        record.setRequestFingerprint("fingerprint-" + requestId);
        record.setStartedAt(startedAt);
        record.setDeadlineAt(deadlineAt);
        record.setHeartbeatAt(heartbeatAt);
        return record;
    }

    private AgentRequest fingerprintRequest(String query,
                                            String executionMode,
                                            String outputStyle,
                                            String modelId,
                                            boolean online,
                                            String aiAgentId,
                                            List<FileInformation> files) {
        return AgentRequest.builder()
                .query(query)
                .originalQuery(query)
                .executionMode(executionMode)
                .outputStyle(outputStyle)
                .modelId(modelId)
                .online(online)
                .aiAgentId(aiAgentId)
                .sessionFiles(files)
                .build();
    }

    private FileInformation file(String fileName, String resourceKey, String ossUrl) {
        return FileInformation.builder()
                .fileName(fileName)
                .originFileName(fileName)
                .resourceKey(resourceKey)
                .ossUrl(ossUrl)
                .fileSize(128)
                .mimeType("application/octet-stream")
                .build();
    }

    private AgentRequest request(String requestId, String sessionId, String ownerId) {
        return AgentRequest.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .ownerId(ownerId)
                .query("idempotent request")
                .executionMode("STANDARD")
                .build();
    }

    @SuppressWarnings("unchecked")
    private String messageType(GptProcessResult frame) {
        Map<String, Object> eventData = (Map<String, Object>) frame.getResultMap().get("eventData");
        Map<String, Object> resultMap = (Map<String, Object>) eventData.get("resultMap");
        return String.valueOf(resultMap.get("messageType"));
    }
}
