package com.linrun.agent.infrastructure.tool.durable;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolOutboxMessage;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolWakeupPublisher;

/** Local/dev fallback. The database poller retains the exact same outbox contract without Kafka. */
@Slf4j
@Component
@ConditionalOnProperty(name = "aigroup.durable-tool.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingDurableToolWakeupPublisher implements DurableToolWakeupPublisher {

    @Override
    public void publish(DurableToolOutboxMessage message) {
        log.debug("durable tool outbox wake-up queued invocationId={} outboxId={}",
                message.getToolInvocationId(), message.getId());
    }
}
