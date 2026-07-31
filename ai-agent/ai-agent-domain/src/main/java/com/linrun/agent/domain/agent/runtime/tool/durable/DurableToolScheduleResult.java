package com.linrun.agent.domain.agent.runtime.tool.durable;

import lombok.Builder;
import lombok.Value;

/** Admission result. A reused operation never creates an outbox message or a new worker attempt. */
@Value
@Builder
public class DurableToolScheduleResult {

    DurableToolInvocation invocation;
    boolean reused;
}
