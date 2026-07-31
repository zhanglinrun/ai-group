package com.linrun.agent.domain.agent.runtime.tool.durable;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Recoverable projection; it intentionally contains no prompt, context, or hidden reasoning. */
@Value
@Builder
public class DurableToolInvocation {

    Long toolInvocationId;
    Long runId;
    String requestId;
    String toolCallId;
    String toolName;
    String operationKey;
    DurableToolExecutionMode executionMode;
    Long sourceInvocationId;
    DurableToolStatus status;
    long fencingToken;
    boolean retryable;
    String resultPayload;
    String resultHash;
    String errorType;
    Instant leaseExpiresAt;
    Instant heartbeatAt;
}
