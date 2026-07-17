package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.response.AgentResponse;

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

        printer.send("verification_result", Map.of(
                "passed", false,
                "reasons", List.of("todo remains"),
                "requiredActions", List.of("continue current todo"),
                "verifierExecuted", true
        ));

        AgentResponse response = (AgentResponse) payload.get();
        Assert.assertEquals("verification_result", response.getMessageType());
        Assert.assertEquals(Boolean.FALSE, response.getResultMap().get("passed"));
        Assert.assertEquals(List.of("todo remains"), response.getResultMap().get("reasons"));
        Assert.assertFalse(response.getResultMap().containsKey("agentType"));
    }
}
