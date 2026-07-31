package com.linrun.agent.domain.agent.runtime.tool.durable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic store used by domain tests. Production uses the JDBC adapter;
 * keeping this implementation in the domain makes the state machine testable
 * without pretending an in-memory map provides production durability.
 */
public final class InMemoryDurableToolStore implements DurableToolStore {

    private final Map<Long, DurableToolInvocation> invocations = new HashMap<>();
    private final Map<String, Long> executedOperationSources = new HashMap<>();
    private final Map<Long, List<DurableToolAttempt>> attempts = new HashMap<>();
    private final Map<Long, DurableToolOutboxMessage> outbox = new HashMap<>();
    private final AtomicLong outboxSequence = new AtomicLong();
    private final AtomicLong attemptSequence = new AtomicLong();

    @Override
    public synchronized DurableToolScheduleResult schedule(DurableToolExecutionRequest request, Instant now) {
        String key = request.getRunId() + "|" + request.getOperationKey();
        Long sourceId = executedOperationSources.get(key);
        if (sourceId != null) {
            DurableToolInvocation source = invocations.get(sourceId);
            DurableToolInvocation reused = DurableToolInvocation.builder()
                    .toolInvocationId(request.getToolInvocationId())
                    .runId(request.getRunId())
                    .requestId(request.getRequestId())
                    .toolCallId(request.getToolCallId())
                    .toolName(request.getToolName())
                    .operationKey(request.getOperationKey())
                    .executionMode(DurableToolExecutionMode.REUSED)
                    .sourceInvocationId(sourceId)
                    .status(source == null ? DurableToolStatus.UNKNOWN : source.getStatus())
                    .fencingToken(request.getFencingToken())
                    .retryable(false)
                    .resultPayload(source == null ? null : source.getResultPayload())
                    .resultHash(source == null ? null : source.getResultHash())
                    .errorType(source == null ? "SOURCE_INVOCATION_MISSING" : source.getErrorType())
                    .heartbeatAt(now)
                    .build();
            invocations.put(request.getToolInvocationId(), reused);
            return DurableToolScheduleResult.builder().invocation(reused).reused(true).build();
        }

        DurableToolInvocation invocation = DurableToolInvocation.builder()
                .toolInvocationId(request.getToolInvocationId())
                .runId(request.getRunId())
                .requestId(request.getRequestId())
                .toolCallId(request.getToolCallId())
                .toolName(request.getToolName())
                .operationKey(request.getOperationKey())
                .executionMode(DurableToolExecutionMode.EXECUTED)
                .status(DurableToolStatus.SCHEDULED)
                .fencingToken(request.getFencingToken())
                .retryable(request.isRetryable())
                .heartbeatAt(now)
                .build();
        invocations.put(request.getToolInvocationId(), invocation);
        executedOperationSources.put(key, request.getToolInvocationId());
        long outboxId = outboxSequence.incrementAndGet();
        outbox.put(outboxId, DurableToolOutboxMessage.builder()
                .id(outboxId)
                .toolInvocationId(request.getToolInvocationId())
                .operationKey(request.getOperationKey())
                .status(DurableToolOutboxStatus.SCHEDULED)
                .retryCount(0)
                .nextAttemptAt(now)
                .build());
        return DurableToolScheduleResult.builder().invocation(invocation).reused(false).build();
    }

    @Override
    public synchronized Optional<DurableToolInvocation> findInvocation(Long toolInvocationId) {
        return Optional.ofNullable(invocations.get(toolInvocationId));
    }

    @Override
    public synchronized DurableToolAttempt startAttempt(Long toolInvocationId,
                                                         String workerId,
                                                         long fencingToken,
                                                         Instant now,
                                                         Instant leaseExpiresAt) {
        DurableToolInvocation invocation = requireInvocable(toolInvocationId, fencingToken);
        List<DurableToolAttempt> history = attempts.computeIfAbsent(toolInvocationId, ignored -> new ArrayList<>());
        DurableToolAttempt attempt = DurableToolAttempt.builder()
                .id(attemptSequence.incrementAndGet())
                .toolInvocationId(toolInvocationId)
                .attemptNo(history.size() + 1)
                .workerId(workerId)
                .fencingToken(fencingToken)
                .status(DurableToolStatus.RUNNING)
                .startedAt(now)
                .heartbeatAt(now)
                .build();
        history.add(attempt);
        invocations.put(toolInvocationId, copy(invocation)
                .status(DurableToolStatus.RUNNING)
                .leaseExpiresAt(leaseExpiresAt)
                .heartbeatAt(now)
                .build());
        return attempt;
    }

    @Override
    public synchronized boolean heartbeat(Long toolInvocationId,
                                          int attemptNo,
                                          String workerId,
                                          long fencingToken,
                                          Instant now,
                                          Instant leaseExpiresAt) {
        DurableToolInvocation invocation = invocations.get(toolInvocationId);
        if (invocation == null || invocation.getStatus() != DurableToolStatus.RUNNING
                || invocation.getFencingToken() != fencingToken) {
            return false;
        }
        List<DurableToolAttempt> history = attempts.get(toolInvocationId);
        DurableToolAttempt attempt = attempt(history, attemptNo);
        if (attempt == null || attempt.getFencingToken() != fencingToken || !workerId.equals(attempt.getWorkerId())) {
            return false;
        }
        replaceAttempt(history, copy(attempt).heartbeatAt(now).build());
        invocations.put(toolInvocationId, copy(invocation)
                .heartbeatAt(now)
                .leaseExpiresAt(leaseExpiresAt)
                .build());
        return true;
    }

    @Override
    public synchronized DurableToolCallbackResult complete(DurableToolWorkerCallback callback) {
        DurableToolInvocation invocation = invocations.get(callback.getToolInvocationId());
        if (invocation == null) {
            return DurableToolCallbackResult.NOT_FOUND;
        }
        if (invocation.getFencingToken() != callback.getFencingToken()) {
            return DurableToolCallbackResult.FENCE_REJECTED;
        }
        if (invocation.getStatus().isTerminal()) {
            return DurableToolCallbackResult.DUPLICATE;
        }
        if (invocation.getStatus() != DurableToolStatus.RUNNING
                && invocation.getStatus() != DurableToolStatus.CANCEL_REQUESTED) {
            return DurableToolCallbackResult.INVALID_STATE;
        }
        List<DurableToolAttempt> history = attempts.get(callback.getToolInvocationId());
        DurableToolAttempt attempt = attempt(history, callback.getAttemptNo());
        if (attempt == null || attempt.getFencingToken() != callback.getFencingToken()
                || !callback.getWorkerId().equals(attempt.getWorkerId())) {
            return DurableToolCallbackResult.FENCE_REJECTED;
        }
        Instant finishedAt = callback.getOccurredAt() == null ? Instant.now() : callback.getOccurredAt();
        String resultHash = callback.getResultHash();
        if ((resultHash == null || resultHash.isBlank()) && callback.getResultPayload() != null) {
            resultHash = sha256(callback.getResultPayload());
        }
        replaceAttempt(history, copy(attempt)
                .providerRequestId(callback.getProviderRequestId())
                .status(callback.getStatus())
                .errorType(callback.getErrorType())
                .resultHash(resultHash)
                .finishedAt(finishedAt)
                .heartbeatAt(finishedAt)
                .build());
        invocations.put(callback.getToolInvocationId(), copy(invocation)
                .status(callback.getStatus())
                .resultPayload(callback.getResultPayload())
                .resultHash(resultHash)
                .errorType(callback.getErrorType())
                .heartbeatAt(finishedAt)
                .leaseExpiresAt(null)
                .build());
        markOutboxAcknowledged(callback.getToolInvocationId(), finishedAt);
        return DurableToolCallbackResult.ACCEPTED;
    }

    @Override
    public synchronized boolean requestCancellation(Long toolInvocationId, long fencingToken, Instant now) {
        DurableToolInvocation invocation = invocations.get(toolInvocationId);
        if (invocation == null || invocation.getFencingToken() != fencingToken || invocation.getStatus().isTerminal()) {
            return false;
        }
        invocations.put(toolInvocationId, copy(invocation)
                .status(DurableToolStatus.CANCEL_REQUESTED)
                .heartbeatAt(now)
                .build());
        return true;
    }

    @Override
    public synchronized List<DurableToolOutboxMessage> dueOutbox(Instant now, int limit) {
        return outbox.values().stream()
                .filter(message -> (message.getStatus() == DurableToolOutboxStatus.SCHEDULED
                        || message.getStatus() == DurableToolOutboxStatus.RETRY)
                        && !message.getNextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(DurableToolOutboxMessage::getId))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void markOutboxPublished(Long outboxId, Instant now) {
        DurableToolOutboxMessage message = outbox.get(outboxId);
        if (message == null || message.getStatus() == DurableToolOutboxStatus.ACKNOWLEDGED) {
            return;
        }
        outbox.put(outboxId, DurableToolOutboxMessage.builder()
                .id(message.getId())
                .toolInvocationId(message.getToolInvocationId())
                .operationKey(message.getOperationKey())
                .status(DurableToolOutboxStatus.PUBLISHED)
                .retryCount(message.getRetryCount())
                .nextAttemptAt(now.plusSeconds(30))
                .publishedAt(now)
                .acknowledgedAt(message.getAcknowledgedAt())
                .build());
    }

    @Override
    public synchronized void markOutboxRetry(Long outboxId, Instant nextAttemptAt) {
        DurableToolOutboxMessage message = outbox.get(outboxId);
        if (message == null || message.getStatus() == DurableToolOutboxStatus.ACKNOWLEDGED) {
            return;
        }
        outbox.put(outboxId, DurableToolOutboxMessage.builder()
                .id(message.getId())
                .toolInvocationId(message.getToolInvocationId())
                .operationKey(message.getOperationKey())
                .status(DurableToolOutboxStatus.RETRY)
                .retryCount(message.getRetryCount() + 1)
                .nextAttemptAt(nextAttemptAt)
                .publishedAt(message.getPublishedAt())
                .acknowledgedAt(message.getAcknowledgedAt())
                .build());
    }

    @Override
    public synchronized void markOutboxAcknowledged(Long toolInvocationId, Instant now) {
        outbox.replaceAll((id, message) -> message.getToolInvocationId().equals(toolInvocationId)
                ? DurableToolOutboxMessage.builder()
                .id(message.getId())
                .toolInvocationId(message.getToolInvocationId())
                .operationKey(message.getOperationKey())
                .status(DurableToolOutboxStatus.ACKNOWLEDGED)
                .retryCount(message.getRetryCount())
                .nextAttemptAt(message.getNextAttemptAt())
                .publishedAt(message.getPublishedAt())
                .acknowledgedAt(now)
                .build()
                : message);
    }

    @Override
    public synchronized List<DurableToolInvocation> expiredRunning(Instant now, int limit) {
        return invocations.values().stream()
                .filter(invocation -> invocation.getStatus() == DurableToolStatus.RUNNING
                        && invocation.getLeaseExpiresAt() != null
                        && invocation.getLeaseExpiresAt().isBefore(now))
                .sorted(Comparator.comparing(DurableToolInvocation::getToolInvocationId))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean markUnknown(Long toolInvocationId,
                                            long fencingToken,
                                            String errorType,
                                            Instant now) {
        DurableToolInvocation invocation = invocations.get(toolInvocationId);
        if (invocation == null || invocation.getFencingToken() != fencingToken
                || invocation.getStatus().isTerminal()) {
            return false;
        }
        invocations.put(toolInvocationId, copy(invocation)
                .status(DurableToolStatus.UNKNOWN)
                .errorType(errorType)
                .heartbeatAt(now)
                .leaseExpiresAt(null)
                .build());
        List<DurableToolAttempt> history = attempts.get(toolInvocationId);
        if (history != null && !history.isEmpty()) {
            DurableToolAttempt latest = history.get(history.size() - 1);
            replaceAttempt(history, copy(latest)
                    .status(DurableToolStatus.UNKNOWN)
                    .errorType(errorType)
                    .finishedAt(now)
                    .build());
        }
        return true;
    }

    private DurableToolInvocation requireInvocable(Long toolInvocationId, long fencingToken) {
        DurableToolInvocation invocation = invocations.get(toolInvocationId);
        if (invocation == null || invocation.getFencingToken() != fencingToken
                || invocation.getStatus() != DurableToolStatus.SCHEDULED) {
            throw new IllegalStateException("durable tool invocation is not schedulable");
        }
        return invocation;
    }

    private DurableToolAttempt attempt(List<DurableToolAttempt> history, int attemptNo) {
        if (history == null) {
            return null;
        }
        return history.stream().filter(item -> item.getAttemptNo() == attemptNo).findFirst().orElse(null);
    }

    private void replaceAttempt(List<DurableToolAttempt> history, DurableToolAttempt updated) {
        for (int index = 0; index < history.size(); index++) {
            if (history.get(index).getAttemptNo() == updated.getAttemptNo()) {
                history.set(index, updated);
                return;
            }
        }
    }

    private DurableToolInvocation.DurableToolInvocationBuilder copy(DurableToolInvocation source) {
        return DurableToolInvocation.builder()
                .toolInvocationId(source.getToolInvocationId())
                .runId(source.getRunId())
                .requestId(source.getRequestId())
                .toolCallId(source.getToolCallId())
                .toolName(source.getToolName())
                .operationKey(source.getOperationKey())
                .executionMode(source.getExecutionMode())
                .sourceInvocationId(source.getSourceInvocationId())
                .status(source.getStatus())
                .fencingToken(source.getFencingToken())
                .retryable(source.isRetryable())
                .resultPayload(source.getResultPayload())
                .resultHash(source.getResultHash())
                .errorType(source.getErrorType())
                .leaseExpiresAt(source.getLeaseExpiresAt())
                .heartbeatAt(source.getHeartbeatAt());
    }

    private DurableToolAttempt.DurableToolAttemptBuilder copy(DurableToolAttempt source) {
        return DurableToolAttempt.builder()
                .id(source.getId())
                .toolInvocationId(source.getToolInvocationId())
                .attemptNo(source.getAttemptNo())
                .workerId(source.getWorkerId())
                .fencingToken(source.getFencingToken())
                .providerRequestId(source.getProviderRequestId())
                .status(source.getStatus())
                .errorType(source.getErrorType())
                .resultHash(source.getResultHash())
                .startedAt(source.getStartedAt())
                .finishedAt(source.getFinishedAt())
                .heartbeatAt(source.getHeartbeatAt());
    }

    private String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
