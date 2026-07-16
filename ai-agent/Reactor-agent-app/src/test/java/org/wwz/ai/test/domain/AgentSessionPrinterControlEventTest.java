package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.response.AgentResponse;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSessionPrinterControlEventTest {

    @Test
    public void shouldPreserveCheckpointControlFieldsInStreamFrame() {
        AgentResponse response = capture("checkpoint", Map.of(
                "checkpointId", "checkpoint-001",
                "phase", "READY_FOR_STEP",
                "sequence", 3,
                "nextStepIndex", 2,
                "resumable", true
        ));

        Assert.assertEquals("checkpoint", response.getMessageType());
        Assert.assertEquals("checkpoint-001", response.getResultMap().get("checkpointId"));
        Assert.assertEquals("READY_FOR_STEP", response.getResultMap().get("phase"));
        Assert.assertEquals(3, response.getResultMap().get("sequence"));
        Assert.assertEquals(2, response.getResultMap().get("nextStepIndex"));
        Assert.assertEquals(true, response.getResultMap().get("resumable"));
        Assert.assertEquals(3, response.getResultMap().get("agentType"));
    }

    @Test
    public void shouldPreserveResumeControlFieldsInStreamFrame() {
        AgentResponse response = capture("resume", Map.of(
                "checkpointId", "checkpoint-002",
                "sourceRequestId", "request-source-001",
                "phase", "BEFORE_SUMMARY",
                "resumeDecision", "SAFE_ONLY"
        ));

        Assert.assertEquals("resume", response.getMessageType());
        Assert.assertEquals("checkpoint-002", response.getResultMap().get("checkpointId"));
        Assert.assertEquals("request-source-001", response.getResultMap().get("sourceRequestId"));
        Assert.assertEquals("BEFORE_SUMMARY", response.getResultMap().get("phase"));
        Assert.assertEquals("SAFE_ONLY", response.getResultMap().get("resumeDecision"));
    }

    private AgentResponse capture(String messageType, Map<String, Object> message) {
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
                AgentRequest.builder().requestId("req-control-stream-001").build(),
                3
        );

        printer.send(messageType, message);

        return (AgentResponse) payload.get();
    }
}
