package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointCoordinator;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointPhase;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointProperties;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointRepository;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointState;
import org.wwz.ai.domain.agent.checkpoint.PlanExecutionCheckpoint;
import org.wwz.ai.domain.agent.checkpoint.PlanResumeApprovalRequiredException;
import org.wwz.ai.domain.agent.checkpoint.PlanResumeDecision;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.AgentRunState;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.Plan;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PlanCheckpointCoordinatorTest {

    private InMemoryCheckpointRepository repository;
    private IExecutionLedgerReadRepository ledger;
    private PlanCheckpointCoordinator coordinator;

    @Before
    public void setUp() {
        repository = new InMemoryCheckpointRepository();
        ledger = Mockito.mock(IExecutionLedgerReadRepository.class);
        PlanCheckpointProperties properties = new PlanCheckpointProperties();
        properties.setEnabled(true);
        properties.setReplaySafeTools(List.of("web_fetch"));
        coordinator = new PlanCheckpointCoordinator(repository, ledger, properties);
    }

    @Test
    public void shouldSaveAndResumeOwnedSafeCheckpointOnce() {
        AgentContext context = activeContext();
        PlanExecutionCheckpoint saved = coordinator.save(
                        context,
                        PlanCheckpointPhase.READY_FOR_STEP,
                        1,
                        state("next task"))
                .orElseThrow();
        Mockito.when(ledger.queryToolInvocationsByRunId(99L)).thenReturn(List.of());

        PlanExecutionCheckpoint resumed = coordinator.resume(
                saved.getCheckpointId(), "7", "session-1", "request-resume-1", PlanResumeDecision.SAFE_ONLY);

        Assert.assertEquals("request-resume-1", resumed.getResumedByRequestId());
        Assert.assertEquals(PlanResumeDecision.SAFE_ONLY, resumed.getResumeDecision());

        try {
            coordinator.resume(saved.getCheckpointId(), "7", "session-1", "request-resume-2",
                    PlanResumeDecision.SAFE_ONLY);
            Assert.fail("a checkpoint must not be consumed by a second request");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("already been consumed"));
        }
    }

    @Test
    public void shouldRequireHumanDecisionForUnknownSideEffectTool() {
        AgentContext context = activeContext();
        PlanExecutionCheckpoint saved = coordinator.save(
                        context,
                        PlanCheckpointPhase.READY_FOR_STEP,
                        0,
                        state("write report"))
                .orElseThrow();
        Mockito.when(ledger.queryToolInvocationsByRunId(99L)).thenReturn(List.of(
                ToolInvocation.builder()
                        .toolName("report_tool")
                        .startedAt(saved.getCreatedAt().plusSeconds(1))
                        .build()));

        try {
            coordinator.resume(saved.getCheckpointId(), "7", "session-1", "request-resume-safe",
                    PlanResumeDecision.SAFE_ONLY);
            Assert.fail("unknown side effects must stop automatic replay");
        } catch (PlanResumeApprovalRequiredException expected) {
            Assert.assertEquals(List.of("report_tool"), expected.getAmbiguousTools());
        }

        PlanExecutionCheckpoint approved = coordinator.resume(
                saved.getCheckpointId(), "7", "session-1", "request-resume-approved",
                PlanResumeDecision.RESTART_FROM_CHECKPOINT);
        Assert.assertEquals(PlanResumeDecision.RESTART_FROM_CHECKPOINT, approved.getResumeDecision());
    }

    @Test
    public void shouldAllowConfiguredReadOnlyToolWithoutApproval() {
        AgentContext context = activeContext();
        PlanExecutionCheckpoint saved = coordinator.save(
                        context,
                        PlanCheckpointPhase.READY_FOR_STEP,
                        0,
                        state("research"))
                .orElseThrow();
        Mockito.when(ledger.queryToolInvocationsByRunId(99L)).thenReturn(List.of(
                ToolInvocation.builder()
                        .toolName("web_fetch")
                        .startedAt(saved.getCreatedAt().plusSeconds(1))
                        .build()));

        PlanExecutionCheckpoint resumed = coordinator.resume(
                saved.getCheckpointId(), "7", "session-1", "request-resume-read", PlanResumeDecision.SAFE_ONLY);
        Assert.assertEquals("request-resume-read", resumed.getResumedByRequestId());
    }

    @Test
    public void shouldHideCheckpointAcrossOwnerBoundary() {
        PlanExecutionCheckpoint saved = coordinator.save(
                        activeContext(),
                        PlanCheckpointPhase.READY_FOR_STEP,
                        0,
                        state("task"))
                .orElseThrow();
        try {
            coordinator.inspectForResume(saved.getCheckpointId(), "8", "session-1");
            Assert.fail("another owner must not discover a checkpoint");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("not found"));
        }
    }

    @Test
    public void shouldRequireApprovalWhenSourceRunStillLooksActive() {
        PlanExecutionCheckpoint saved = coordinator.save(
                        activeContext(),
                        PlanCheckpointPhase.READY_FOR_STEP,
                        0,
                        state("task"))
                .orElseThrow();
        Mockito.when(ledger.queryRunByRequestId("request-1"))
                .thenReturn(DialogueRun.builder().status(ExecutionLedgerConstants.STATUS_RUNNING).build());
        Mockito.when(ledger.queryToolInvocationsByRunId(99L)).thenReturn(List.of());

        try {
            coordinator.resume(saved.getCheckpointId(), "7", "session-1", "resume-active",
                    PlanResumeDecision.SAFE_ONLY);
            Assert.fail("an apparently active source run must not be duplicated automatically");
        } catch (PlanResumeApprovalRequiredException expected) {
            Assert.assertEquals(List.of("SOURCE_RUN_STILL_RUNNING"), expected.getAmbiguousTools());
        }
    }

    private AgentContext activeContext() {
        return AgentContext.builder()
                .requestId("request-1")
                .sessionId("session-1")
                .ownerId(7L)
                .executionRecorder(Mockito.mock(AgentExecutionRecorder.class))
                .agentRunState(AgentRunState.builder().runId(99L).build())
                .build();
    }

    private PlanCheckpointState state(String nextTask) {
        return PlanCheckpointState.builder()
                .originalQuery("research question")
                .nextTask(nextTask)
                .nextStepIndex(0)
                .plan(Plan.builder()
                        .title("test")
                        .steps(List.of(nextTask))
                        .stepStatus(List.of("in_progress"))
                        .notes(List.of(""))
                        .build())
                .build();
    }

    private static final class InMemoryCheckpointRepository implements PlanCheckpointRepository {

        private final Map<String, PlanExecutionCheckpoint> rows = new LinkedHashMap<>();
        private long idSequence = 1L;

        @Override
        public synchronized PlanExecutionCheckpoint save(PlanExecutionCheckpoint checkpoint) {
            checkpoint.setId(idSequence++);
            checkpoint.setCreatedAt(LocalDateTime.now());
            checkpoint.setUpdatedAt(checkpoint.getCreatedAt());
            rows.put(checkpoint.getCheckpointId(), checkpoint);
            return checkpoint;
        }

        @Override
        public synchronized Optional<PlanExecutionCheckpoint> findOwned(String checkpointId,
                                                                        String ownerId,
                                                                        String sessionId) {
            PlanExecutionCheckpoint checkpoint = rows.get(checkpointId);
            if (checkpoint == null || !ownerId.equals(checkpoint.getOwnerId())
                    || !sessionId.equals(checkpoint.getSessionId())) {
                return Optional.empty();
            }
            return Optional.of(checkpoint);
        }

        @Override
        public synchronized boolean claimForResume(String checkpointId,
                                                   String ownerId,
                                                   String sessionId,
                                                   String resumedByRequestId,
                                                   PlanResumeDecision decision) {
            Optional<PlanExecutionCheckpoint> owned = findOwned(checkpointId, ownerId, sessionId);
            if (owned.isEmpty() || !Boolean.TRUE.equals(owned.get().getResumable())) {
                return false;
            }
            PlanExecutionCheckpoint checkpoint = owned.get();
            if (checkpoint.getResumedByRequestId() != null
                    && !resumedByRequestId.equals(checkpoint.getResumedByRequestId())) {
                return false;
            }
            checkpoint.setResumedByRequestId(resumedByRequestId);
            checkpoint.setResumeDecision(decision);
            checkpoint.setResumedAt(LocalDateTime.now());
            return true;
        }

        @Override
        public synchronized void markRunCompleted(Long runId) {
            rows.values().stream()
                    .filter(row -> runId.equals(row.getRunId()))
                    .forEach(row -> row.setResumable(Boolean.FALSE));
        }
    }
}
