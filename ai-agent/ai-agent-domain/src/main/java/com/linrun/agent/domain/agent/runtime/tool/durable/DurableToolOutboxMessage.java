package com.linrun.agent.domain.agent.runtime.tool.durable;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Durable worker wake-up command. It may be delivered by Kafka or by the DB poller. */
@Value
@Builder
public class DurableToolOutboxMessage {

    Long id;
    Long toolInvocationId;
    String operationKey;
    DurableToolOutboxStatus status;
    int retryCount;
    Instant nextAttemptAt;
    Instant publishedAt;
    Instant acknowledgedAt;
}
