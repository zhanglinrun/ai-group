package com.linrun.agent.domain.agent.runtime.tool.durable;

/** Persistent wake-up delivery lifecycle. MySQL remains the source of truth. */
public enum DurableToolOutboxStatus {
    SCHEDULED,
    PUBLISHED,
    ACKNOWLEDGED,
    RETRY
}
