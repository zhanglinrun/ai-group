package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.response.AgentResponse;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentSessionPrinterTerminalStatusTest {

    @Test
    public void shouldPutAuthoritativeFailureOnTopLevelTerminalFrame() {
        RecordingStream stream = new RecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream,
                AgentRequest.builder().requestId("request-terminal").agentType(2).build()
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

    @Test
    public void shouldPropagateTerminalStreamSendFailure() {
        AgentMessageStream failingStream = new AgentMessageStream() {
            @Override
            public void send(Object payload) throws Exception {
                throw new IllegalStateException("serialization failed");
            }

            @Override
            public void complete() {
            }

            @Override
            public void completeWithError(Throwable throwable) {
            }
        };
        AgentSessionPrinter printer = new AgentSessionPrinter(
                failingStream,
                AgentRequest.builder().requestId("request-terminal-failure").build()
        );

        try {
            printer.send("run_finished", Map.of(
                    "status", "SUCCESS",
                    "completionGatePassed", true,
                    "stopReason", "COMPLETED"
            ));
            Assert.fail("terminal send failure must be propagated");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("run_finished"));
        }
    }

    @Test
    public void shouldForwardProjectedReplayEnvelopeWithoutReencoding() {
        RecordingStream stream = new RecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream,
                AgentRequest.builder().requestId("request-replay").build()
        );
        GptProcessResult frame = GptProcessResult.builder()
                .status("success")
                .reqId("request-replay")
                .finished(true)
                .resultMap(Map.of("eventData", Map.of("messageType", "agent_event")))
                .build();

        printer.sendReplayFrame(frame);

        Assert.assertSame(frame, stream.payload);
    }

    private static final class RecordingStream implements AgentMessageStream {
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
