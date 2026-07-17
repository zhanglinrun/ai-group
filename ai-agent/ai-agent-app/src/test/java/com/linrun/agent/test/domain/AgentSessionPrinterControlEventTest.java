package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.response.AgentResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSessionPrinterControlEventTest {

    @Test
    public void shouldPreserveRunStartedFieldsInStreamFrame() {
        AgentResponse response = capture("run_started", Map.of(
                "runId", "run-001",
                "executionMode", "DEEP",
                "phase", "RUNNING"
        ));

        Assert.assertEquals("run_started", response.getMessageType());
        Assert.assertEquals("run-001", response.getResultMap().get("runId"));
        Assert.assertEquals("DEEP", response.getResultMap().get("executionMode"));
        Assert.assertEquals("RUNNING", response.getResultMap().get("phase"));
        Assert.assertFalse(response.getResultMap().containsKey("agentType"));
    }

    @Test
    public void shouldPreserveRunFinishedFieldsInStreamFrame() {
        AgentResponse response = capture("run_finished", Map.of(
                "status", "FAILED",
                "stopReason", "MAX_STEPS",
                "completionGatePassed", false
        ));

        Assert.assertEquals("run_finished", response.getMessageType());
        Assert.assertEquals("FAILED", response.getResultMap().get("status"));
        Assert.assertEquals("MAX_STEPS", response.getResultMap().get("stopReason"));
        Assert.assertEquals(Boolean.FALSE, response.getResultMap().get("completionGatePassed"));
        Assert.assertEquals(Boolean.FALSE, response.getFinish());
    }

    @Test
    public void shouldApplyAuthoritativeTerminalStatusToResultFrame() {
        AgentResponse response = capture("result", new HashMap<>(Map.of(
                "taskSummary", "未能在预算内完成",
                "status", "FAILED",
                "runStatus", "FAILED",
                "errorCode", "MAX_STEPS",
                "errorMessage", "达到最大执行步数"
        )));

        Assert.assertEquals("result", response.getMessageType());
        Assert.assertEquals("未能在预算内完成", response.getResult());
        Assert.assertEquals("FAILED", response.getStatus());
        Assert.assertEquals("MAX_STEPS", response.getErrorCode());
        Assert.assertEquals("达到最大执行步数", response.getErrorMessage());
        Assert.assertEquals(Boolean.TRUE, response.getFinish());
    }

    private AgentResponse capture(String messageType, Map<String, Object> message) {
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

        printer.send(messageType, message);

        return (AgentResponse) payload.get();
    }
}
