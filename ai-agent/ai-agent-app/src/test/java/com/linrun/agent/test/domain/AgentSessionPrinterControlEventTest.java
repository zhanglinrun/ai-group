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

public class AgentSessionPrinterControlEventTest {

    @Test
    public void shouldPreserveRunStartedFieldsInStreamFrame() {
        AgentStreamEvent.AgentStart response = (AgentStreamEvent.AgentStart) capture(
                new AgentStreamEvent.AgentStart("run-001", "owner-1", "conversation-1",
                        "AgentLoop", "qwen-plus"));

        Assert.assertEquals("agent_start", response.type());
        Assert.assertEquals("run-001", response.runId());
        Assert.assertEquals("qwen-plus", response.modelId());
    }

    @Test
    public void shouldPreserveRunFinishedFieldsInStreamFrame() {
        AgentStreamEvent.StageOutput response = (AgentStreamEvent.StageOutput) capture(
                new AgentStreamEvent.StageOutput("run-001", null, "run_finished", Map.of(
                        "status", "FAILED",
                        "stopReason", "MAX_STEPS",
                        "completionGatePassed", false), List.of(), true));

        Assert.assertEquals("stage_output", response.type());
        Assert.assertEquals("run_finished", response.outputType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.payload();
        Assert.assertEquals("FAILED", payload.get("status"));
        Assert.assertEquals("MAX_STEPS", payload.get("stopReason"));
        Assert.assertEquals(Boolean.FALSE, payload.get("completionGatePassed"));
    }

    @Test
    public void shouldApplyAuthoritativeTerminalStatusToResultFrame() {
        AgentStreamEvent.Error response = (AgentStreamEvent.Error) capture(
                new AgentStreamEvent.Error("run-001", "MAX_STEPS", "达到最大执行步数"));

        Assert.assertEquals("error", response.type());
        Assert.assertEquals("MAX_STEPS", response.code());
        Assert.assertEquals("达到最大执行步数", response.message());
    }

    private Object capture(AgentStreamEvent event) {
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
                AgentRequest.builder().requestId("req-control-stream-001").build()
        );

        printer.send(event);

        return payload.get();
    }
}
