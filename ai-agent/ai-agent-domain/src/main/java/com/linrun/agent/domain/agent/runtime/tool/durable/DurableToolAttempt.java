package com.linrun.agent.domain.agent.runtime.tool.durable;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** A single remote delivery attempt. Attempts are history, never overwritten by retries. */
@Value
@Builder
public class DurableToolAttempt {

    Long id;
    Long toolInvocationId;
    int attemptNo;
    String workerId;
    long fencingToken;
    String providerRequestId;
    DurableToolStatus status;
    String errorType;
    String resultHash;
    Instant startedAt;
    Instant finishedAt;
    Instant heartbeatAt;
}
