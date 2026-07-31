package com.linrun.agent.domain.agent.runtime.tool.durable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for durable tools. Implementations must make schedule and
 * outbox creation atomic; Kafka is only a wake-up optimization.
 */
public interface DurableToolStore {

    DurableToolScheduleResult schedule(DurableToolExecutionRequest request, Instant now);

    Optional<DurableToolInvocation> findInvocation(Long toolInvocationId);

    DurableToolAttempt startAttempt(Long toolInvocationId,
                                    String workerId,
                                    long fencingToken,
                                    Instant now,
                                    Instant leaseExpiresAt);

    boolean heartbeat(Long toolInvocationId,
                      int attemptNo,
                      String workerId,
                      long fencingToken,
                      Instant now,
                      Instant leaseExpiresAt);

    DurableToolCallbackResult complete(DurableToolWorkerCallback callback);

    boolean requestCancellation(Long toolInvocationId, long fencingToken, Instant now);

    List<DurableToolOutboxMessage> dueOutbox(Instant now, int limit);

    void markOutboxPublished(Long outboxId, Instant now);

    void markOutboxRetry(Long outboxId, Instant nextAttemptAt);

    void markOutboxAcknowledged(Long toolInvocationId, Instant now);

    List<DurableToolInvocation> expiredRunning(Instant now, int limit);

    boolean markUnknown(Long toolInvocationId, long fencingToken, String errorType, Instant now);
}
