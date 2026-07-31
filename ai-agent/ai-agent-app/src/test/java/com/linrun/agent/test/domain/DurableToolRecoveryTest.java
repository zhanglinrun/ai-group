package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolCallbackResult;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolControlPlane;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolExecutionRequest;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolScheduleResult;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolStatus;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolWorkerCallback;
import com.linrun.agent.domain.agent.runtime.tool.durable.InMemoryDurableToolStore;

import java.time.Duration;
import java.time.Instant;

/** P50 state-machine tests: reuse, duplicate callback, fencing and unknown recovery. */
public class DurableToolRecoveryTest {

    @Test
    public void sameOperationKeyShouldReuseCompletedResultWithoutSecondAttempt() {
        DurableToolControlPlane controlPlane = new DurableToolControlPlane(new InMemoryDurableToolStore());
        DurableToolExecutionRequest first = request(101L, "call-1", "sha256:same");
        DurableToolScheduleResult scheduled = controlPlane.schedule(first);
        Assert.assertFalse(scheduled.isReused());
        var attempt = controlPlane.startAttempt(101L, "worker-a", 7L);
        Assert.assertEquals(DurableToolCallbackResult.ACCEPTED, controlPlane.complete(callback(
                101L, attempt.getAttemptNo(), 7L, DurableToolStatus.SUCCEEDED, "{\"evidenceCandidates\":[]}")));

        DurableToolScheduleResult reused = controlPlane.schedule(request(102L, "call-2", "sha256:same"));

        Assert.assertTrue(reused.isReused());
        Assert.assertEquals(DurableToolStatus.SUCCEEDED, reused.getInvocation().getStatus());
        Assert.assertEquals(Long.valueOf(101L), reused.getInvocation().getSourceInvocationId());
        Assert.assertTrue(controlPlane.dueOutbox(10).isEmpty());
    }

    @Test
    public void duplicateCallbackAndStaleFenceMustNotCreateSecondCompletion() {
        DurableToolControlPlane controlPlane = new DurableToolControlPlane(new InMemoryDurableToolStore());
        controlPlane.schedule(request(201L, "call-1", "sha256:one"));
        var attempt = controlPlane.startAttempt(201L, "worker-a", 7L);

        Assert.assertEquals(DurableToolCallbackResult.FENCE_REJECTED, controlPlane.complete(callback(
                201L, attempt.getAttemptNo(), 8L, DurableToolStatus.SUCCEEDED, "{}")));
        Assert.assertEquals(DurableToolCallbackResult.ACCEPTED, controlPlane.complete(callback(
                201L, attempt.getAttemptNo(), 7L, DurableToolStatus.SUCCEEDED, "{}")));
        Assert.assertEquals(DurableToolCallbackResult.DUPLICATE, controlPlane.complete(callback(
                201L, attempt.getAttemptNo(), 7L, DurableToolStatus.SUCCEEDED, "{}")));
    }

    @Test
    public void expiredWorkerMustBecomeUnknownAndRequireManualReconciliation() throws Exception {
        DurableToolControlPlane controlPlane = new DurableToolControlPlane(
                new InMemoryDurableToolStore(), Duration.ofMillis(1));
        controlPlane.schedule(request(301L, "call-1", "sha256:worker-loss"));
        controlPlane.startAttempt(301L, "worker-lost", 7L);
        Thread.sleep(5L);

        var result = controlPlane.reconcile();

        Assert.assertEquals(1, result.markedUnknown());
        Assert.assertEquals(1, result.manualReconciliationRequired());
    }

    private DurableToolExecutionRequest request(Long invocationId, String toolCallId, String operationKey) {
        return DurableToolExecutionRequest.builder()
                .toolInvocationId(invocationId)
                .runId(88L)
                .requestId("request-88")
                .toolCallId(toolCallId)
                .toolName("deep_search")
                .operationKey(operationKey)
                .inputJson("{\"query\":\"durable tool\"}")
                .ownerWorkerId("agent-worker")
                .fencingToken(7L)
                .retryable(true)
                .build();
    }

    private DurableToolWorkerCallback callback(Long invocationId,
                                               int attemptNo,
                                               long fencingToken,
                                               DurableToolStatus status,
                                               String result) {
        return DurableToolWorkerCallback.builder()
                .toolInvocationId(invocationId)
                .attemptNo(attemptNo)
                .workerId("worker-a")
                .fencingToken(fencingToken)
                .providerRequestId("provider-1")
                .status(status)
                .resultPayload(result)
                .occurredAt(Instant.now())
                .build();
    }
}
