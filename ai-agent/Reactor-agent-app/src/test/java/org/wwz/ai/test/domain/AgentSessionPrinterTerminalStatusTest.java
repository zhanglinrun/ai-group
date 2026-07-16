package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.response.AgentResponse;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentSessionPrinterTerminalStatusTest {

    @Test
    public void shouldPutAuthoritativeFailureOnTopLevelTerminalFrame() {
        RecordingStream stream = new RecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream,
                AgentRequest.builder().requestId("request-terminal").agentType(2).build(),
                2
        );
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("taskSummary", "质量评估未通过");
        terminal.put("runStatus", "FAILED");
        terminal.put("errorCode", "PLAN_EVALUATION_REPLAN_EXHAUSTED");
        terminal.put("errorMessage", "重规划次数已耗尽");

        printer.send("result", terminal);

        AgentResponse response = (AgentResponse) stream.payload;
        Assert.assertEquals(Boolean.TRUE, response.getFinish());
        Assert.assertEquals("FAILED", response.getStatus());
        Assert.assertEquals("PLAN_EVALUATION_REPLAN_EXHAUSTED", response.getErrorCode());
        Assert.assertEquals("重规划次数已耗尽", response.getErrorMessage());
        Assert.assertEquals("重规划次数已耗尽", response.getErrorMsg());
    }

    private static final class RecordingStream implements AgentSessionStream {
        private Object payload;

        @Override
        public void send(Object payload) {
            this.payload = payload;
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable throwable) {
        }
    }
}
