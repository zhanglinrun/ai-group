package com.linrun.agent.domain.agent.runtime.tool.durable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * State machine for remote durable tools. In particular, an expired running
 * attempt is moved to UNKNOWN rather than silently replayed.
 */
public final class DurableToolControlPlane {

    private static final int DEFAULT_OUTBOX_BATCH_SIZE = 100;

    private final DurableToolStore store;
    private final Duration leaseDuration;

    public DurableToolControlPlane(DurableToolStore store) {
        this(store, Duration.ofSeconds(30));
    }

    public DurableToolControlPlane(DurableToolStore store, Duration leaseDuration) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.leaseDuration = leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                ? Duration.ofSeconds(30)
                : leaseDuration;
    }

    public DurableToolScheduleResult schedule(DurableToolExecutionRequest request) {
        validateRequest(request);
        return store.schedule(request, Instant.now());
    }

    public DurableToolAttempt startAttempt(Long toolInvocationId,
                                           String workerId,
                                           long fencingToken) {
        Instant now = Instant.now();
        return store.startAttempt(toolInvocationId, workerId, fencingToken, now, now.plus(leaseDuration));
    }

    public boolean heartbeat(Long toolInvocationId,
                             int attemptNo,
                             String workerId,
                             long fencingToken) {
        Instant now = Instant.now();
        return store.heartbeat(toolInvocationId, attemptNo, workerId, fencingToken, now, now.plus(leaseDuration));
    }

    public DurableToolCallbackResult complete(DurableToolWorkerCallback callback) {
        validateCallback(callback);
        return store.complete(callback);
    }

    public boolean requestCancellation(Long toolInvocationId, long fencingToken) {
        return store.requestCancellation(toolInvocationId, fencingToken, Instant.now());
    }

    public List<DurableToolOutboxMessage> dueOutbox(int limit) {
        return store.dueOutbox(Instant.now(), limit <= 0 ? DEFAULT_OUTBOX_BATCH_SIZE : limit);
    }

    public void markOutboxPublished(Long outboxId) {
        store.markOutboxPublished(outboxId, Instant.now());
    }

    public void markOutboxRetry(Long outboxId) {
        store.markOutboxRetry(outboxId, Instant.now().plusSeconds(5));
    }

    public void markOutboxAcknowledged(Long toolInvocationId) {
        store.markOutboxAcknowledged(toolInvocationId, Instant.now());
    }

    public DurableToolReconcileResult reconcile() {
        int unknown = 0;
        for (DurableToolInvocation invocation : store.expiredRunning(Instant.now(), DEFAULT_OUTBOX_BATCH_SIZE)) {
            if (store.markUnknown(invocation.getToolInvocationId(), invocation.getFencingToken(),
                    "WORKER_HEARTBEAT_EXPIRED", Instant.now())) {
                unknown++;
            }
        }
        // UNKNOWN external execution deliberately requires a safe status query or a human decision.
        return new DurableToolReconcileResult(0, unknown, unknown);
    }

    private void validateRequest(DurableToolExecutionRequest request) {
        if (request == null || request.getToolInvocationId() == null || request.getRunId() == null
                || blank(request.getToolCallId()) || blank(request.getToolName())
                || blank(request.getOperationKey()) || request.getFencingToken() <= 0L) {
            throw new IllegalArgumentException("durable tool request requires ledger invocation, run, call, operation and fence");
        }
    }

    private void validateCallback(DurableToolWorkerCallback callback) {
        if (callback == null || callback.getToolInvocationId() == null || callback.getAttemptNo() <= 0
                || blank(callback.getWorkerId()) || callback.getFencingToken() <= 0L
                || callback.getStatus() == null || !callback.getStatus().isTerminal()) {
            throw new IllegalArgumentException("durable tool callback requires terminal status, attempt, worker and fence");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
