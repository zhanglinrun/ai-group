package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.response.AgentResponse;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSessionPrinterEvaluationTest {

    @Test
    public void shouldPreserveEvaluationTelemetryInStreamFrame() {
        AtomicReference<Object> payload = new AtomicReference<>();
        AgentSessionStream stream = new AgentSessionStream() {
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
                AgentRequest.builder().requestId("req-evaluation-stream-001").build(),
                3
        );

        printer.send("evaluation", Map.of(
                "evaluationRound", 2,
                "accepted", true,
                "overallScore", 92,
                "replanRound", 1
        ));

        AgentResponse response = (AgentResponse) payload.get();
        Assert.assertEquals("evaluation", response.getMessageType());
        Assert.assertEquals(92, response.getResultMap().get("overallScore"));
        Assert.assertEquals(1, response.getResultMap().get("replanRound"));
        Assert.assertEquals(3, response.getResultMap().get("agentType"));
    }
}
