package com.linrun.agent.domain.agent.runtime.tool.durable;

/** Kafka wake-up port. Delivery is best effort because tool_outbox remains durable. */
public interface DurableToolWakeupPublisher {

    void publish(DurableToolOutboxMessage message);
}
