package com.linrun.agent.domain.agent.runtime.hitl;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ApprovalGateTest {

    @Test
    public void resolvesOnlineDecisionsAndRevalidatesModifiedArguments() throws Exception {
        ToolApprovalRepository repository = mock(ToolApprovalRepository.class);
        AtomicLong ids = new AtomicLong();
        when(repository.create(any())).thenAnswer(invocation ->
                invocation.<ToolApproval>getArgument(0).toBuilder().id(ids.incrementAndGet()).build());
        when(repository.decide(anyLong(), anyString(), any(), any())).thenReturn(true);
        ApprovalGate gate = new ApprovalGate(repository, 1_000L);

        for (ApprovalDecision decision : new ApprovalDecision[]{
                ApprovalDecision.APPROVED, ApprovalDecision.REJECTED,
                ApprovalDecision.SKIPPED, ApprovalDecision.MODIFIED}) {
            CountDownLatch paused = new CountDownLatch(1);
            CompletableFuture<ApprovalGate.ApprovalResult> waiting = CompletableFuture.supplyAsync(() ->
                    gate.awaitApproval(request("run-" + decision), ignored -> paused.countDown(), () -> false));
            assertTrue(paused.await(1, TimeUnit.SECONDS));
            long id = ids.get();
            String payload = decision == ApprovalDecision.MODIFIED
                    ? "{\"query\":\"changed\",\"apiKey\":\"raw-secret\"}"
                    : null;
            assertTrue(gate.decide(id, "42", decision, payload));
            ApprovalGate.ApprovalResult result = waiting.get(1, TimeUnit.SECONDS);
            assertEquals(decision, result.getDecision());
            if (decision == ApprovalDecision.MODIFIED) {
                assertEquals(payload, result.getModifiedArguments());
            }
            assertFalse(gate.decide(id, "42", decision, payload));
        }

        ArgumentCaptor<String> persistedPayload = ArgumentCaptor.forClass(String.class);
        verify(repository).decide(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.eq(ApprovalDecision.MODIFIED), persistedPayload.capture());
        assertTrue(persistedPayload.getValue().contains("***"));
        assertFalse(persistedPayload.getValue().contains("raw-secret"));
    }

    @Test
    public void approvedAllIsScopedToRunAndTool() throws Exception {
        ToolApprovalRepository repository = mock(ToolApprovalRepository.class);
        when(repository.create(any())).thenAnswer(invocation ->
                invocation.<ToolApproval>getArgument(0).toBuilder().id(7L).build());
        when(repository.decide(7L, "42", ApprovalDecision.APPROVED_ALL, null)).thenReturn(true);
        ApprovalGate gate = new ApprovalGate(repository, 1_000L);
        CountDownLatch paused = new CountDownLatch(1);
        CompletableFuture<ApprovalGate.ApprovalResult> waiting = CompletableFuture.supplyAsync(() ->
                gate.awaitApproval(request("run-a"), ignored -> paused.countDown(), () -> false));
        assertTrue(paused.await(1, TimeUnit.SECONDS));
        assertTrue(gate.decide(7L, "42", ApprovalDecision.APPROVED_ALL, null));
        assertTrue(waiting.get(1, TimeUnit.SECONDS).isApproved());

        ApprovalGate.ApprovalResult cached = gate.awaitApproval(
                request("run-a"), ignored -> {
                    throw new AssertionError("cached approval must not pause");
                }, () -> false);
        assertTrue(cached.isApproved());
        assertEquals(ApprovalDecision.APPROVED_ALL, cached.getDecision());
    }

    @Test
    public void ownerMismatchDisconnectAndDatabaseFailureFailClosed() throws Exception {
        ToolApprovalRepository repository = mock(ToolApprovalRepository.class);
        when(repository.create(any())).thenAnswer(invocation ->
                invocation.<ToolApproval>getArgument(0).toBuilder().id(9L).build());
        when(repository.decide(9L, "other", ApprovalDecision.APPROVED, null)).thenReturn(false);
        ApprovalGate gate = new ApprovalGate(repository, 1_000L);
        CountDownLatch paused = new CountDownLatch(1);
        CompletableFuture<ApprovalGate.ApprovalResult> waiting = CompletableFuture.supplyAsync(() ->
                gate.awaitApproval(request("run-owner"), ignored -> paused.countDown(), () -> true));
        assertTrue(paused.await(1, TimeUnit.SECONDS));
        assertFalse(gate.decide(9L, "other", ApprovalDecision.APPROVED, null));
        ApprovalGate.ApprovalResult disconnected = waiting.get(1, TimeUnit.SECONDS);
        assertTrue(disconnected.isRejected());
        assertEquals(ApprovalDecision.TIMEOUT, disconnected.getDecision());
        verify(repository).timeout(9L);

        ToolApprovalRepository failedRepository = mock(ToolApprovalRepository.class);
        when(failedRepository.create(any())).thenThrow(new IllegalStateException("db unavailable"));
        ApprovalGate.ApprovalResult failed = new ApprovalGate(failedRepository, 50L)
                .awaitApproval(request("run-db"), ignored -> { }, () -> false);
        assertTrue(failed.isRejected());
    }

    private ApprovalGate.ApprovalRequest request(String runId) {
        return ApprovalGate.ApprovalRequest.builder()
                .runId(runId)
                .ownerId("42")
                .toolCallId("call-1")
                .toolName("deep_search")
                .argumentsJson("{\"query\":\"market\",\"token\":\"secret\"}")
                .estimatedMicrocredits(200_000L)
                .approvalRequired(true)
                .build();
    }
}
