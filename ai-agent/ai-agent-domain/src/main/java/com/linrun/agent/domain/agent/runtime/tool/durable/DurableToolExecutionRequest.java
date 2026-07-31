package com.linrun.agent.domain.agent.runtime.tool.durable;

import lombok.Builder;
import lombok.Value;

/** Immutable admission request created after ToolDispatcher has written its normal ledger row. */
@Value
@Builder
public class DurableToolExecutionRequest {

    Long toolInvocationId;
    Long runId;
    String requestId;
    String toolCallId;
    String toolName;
    String operationKey;
    String inputJson;
    String ownerWorkerId;
    long fencingToken;
    boolean retryable;
}
