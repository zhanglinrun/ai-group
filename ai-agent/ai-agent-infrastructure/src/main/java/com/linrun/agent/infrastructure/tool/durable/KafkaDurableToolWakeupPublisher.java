package com.linrun.agent.infrastructure.tool.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolOutboxMessage;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolWakeupPublisher;

/** Kafka is a low-latency wake-up path; callers still persist and scan tool_outbox. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aigroup.durable-tool.kafka.enabled", havingValue = "true")
public class KafkaDurableToolWakeupPublisher implements DurableToolWakeupPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(DurableToolOutboxMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send("aigroup.durable-tool.wakeup", String.valueOf(message.getToolInvocationId()), payload);
        } catch (Exception serializationError) {
            throw new IllegalStateException("could not publish durable tool wake-up", serializationError);
        }
    }
}
