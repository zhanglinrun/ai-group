package com.linrun.agent.domain.agent.runtime.tool.durable;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Worker completion callback. Fence mismatches are rejected before changing durable state. */
@Value
@Builder
public class DurableToolWorkerCallback {

    Long toolInvocationId;
    int attemptNo;
    String workerId;
    long fencingToken;
    String providerRequestId;
    DurableToolStatus status;
    String errorType;
    String resultPayload;
    String resultHash;
    Instant occurredAt;
}
