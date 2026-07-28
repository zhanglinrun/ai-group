package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSessionPrinterVerificationTest {

    @Test
    public void shouldPreserveVerificationResultInStreamFrame() {
        AtomicReference<Object> payload = new AtomicReference<>();
        AgentMessageStream stream = new AgentMessageStream() {
            @Override
            public void send(Object value) {
                payload.set(value);
            }

            @Override
            public void complete() {
            }

            @Override
            public void completeWithError(Throwable throwable) {
            }
        };
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream,
                AgentRequest.builder().requestId("req-verification-stream-001").build()
        );

        printer.send(new AgentStreamEvent.StageOutput(
                "req-verification-stream-001", null, "verification_result", Map.of(
                        "passed", false,
                        "reasons", List.of("todo remains"),
                        "requiredActions", List.of("continue current todo"),
                        "verifierExecuted", true), List.of(), true));

        AgentStreamEvent.StageOutput response = (AgentStreamEvent.StageOutput) payload.get();
        Assert.assertEquals("stage_output", response.type());
        Assert.assertEquals("verification_result", response.outputType());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.payload();
        Assert.assertEquals(Boolean.FALSE, result.get("passed"));
        Assert.assertEquals(List.of("todo remains"), result.get("reasons"));
    }
}
