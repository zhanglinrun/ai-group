package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.replay.DialogueRunReplayService;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class AgentSessionPrinterTerminalStatusTest {

    @Test
    public void shouldPutAuthoritativeFailureOnTopLevelTerminalFrame() {
        RecordingStream stream = new RecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream,
                AgentRequest.builder().requestId("request-terminal").agentType(2).build()
        );
        printer.send(new AgentStreamEvent.Error(
                "request-terminal", "PLAN_EVALUATION_REPLAN_EXHAUSTED", "重规划次数已耗尽"));

        AgentStreamEvent.Error response = (AgentStreamEvent.Error) stream.payload;
        Assert.assertEquals("error", response.type());
        Assert.assertEquals("PLAN_EVALUATION_REPLAN_EXHAUSTED", response.code());
        Assert.assertEquals("重规划次数已耗尽", response.message());
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
            printer.send(new AgentStreamEvent.Error(
                    "request-terminal-failure", "EXECUTION_ERROR", "failed"));
            Assert.fail("terminal send failure must be propagated");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("error"));
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

    @Test
    public void shouldReplayStoredCanonicalJsonByEventName() {
        RecordingStream stream = new RecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream, AgentRequest.builder().requestId("request-replay").build());
        AgentStreamEventStore store = new AgentStreamEventStore() {
            @Override
            public void append(String requestId, String eventType, String eventJson) {
            }

            @Override
            public List<StoredStreamEvent> findByRequestId(String requestId) {
                return List.of(
                        new StoredStreamEvent(1, "text", "{\"type\":\"text\",\"runId\":\"r1\",\"delta\":\"hi\"}"),
                        new StoredStreamEvent(2, "complete", "{\"type\":\"complete\",\"runId\":\"r1\",\"summary\":\"hi\",\"totalDurationMillis\":1,\"microcreditsConsumed\":0}"));
            }
        };
        DialogueRunReplayService replayService = new DialogueRunReplayService(null, null, store);

        replayService.replay(printer, DialogueRunClaim.builder()
                .requestId("request-replay")
                .finalSummaryText("hi")
                .build());

        Assert.assertEquals(List.of("text", "complete"), stream.eventNames);
        Assert.assertEquals("{\"type\":\"text\",\"runId\":\"r1\",\"delta\":\"hi\"}",
                stream.eventPayloads.get(0).toString());
    }

    @Test
    public void shouldPersistCanonicalEventBeforeSendingIt() {
        List<String> order = new ArrayList<>();
        AgentStreamEventStore store = new AgentStreamEventStore() {
            @Override
            public void append(String requestId, String eventType, String eventJson) {
                Assert.assertEquals("request-live", requestId);
                Assert.assertTrue(eventJson.contains("\"type\":\"text\""));
                order.add("persist");
            }

            @Override
            public List<StoredStreamEvent> findByRequestId(String requestId) {
                return List.of();
            }
        };
        AgentMessageStream stream = new AgentMessageStream() {
            @Override
            public void send(Object payload) {
            }

            @Override
            public void send(String eventName, Object payload) {
                order.add("send");
            }

            @Override
            public void complete() {
            }

            @Override
            public void completeWithError(Throwable throwable) {
            }
        };
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream, AgentRequest.builder().requestId("request-live").build(), store);

        printer.send(new AgentStreamEvent.Text("run-1", "hello"));

        Assert.assertEquals(List.of("persist", "send"), order);
    }

    private static final class RecordingStream implements AgentMessageStream {
        private Object payload;
        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> eventPayloads = new ArrayList<>();

        @Override
        public void send(Object payload) {
            this.payload = payload;
        }

        @Override
        public void send(String eventName, Object payload) {
            this.payload = payload;
            this.eventNames.add(eventName);
            this.eventPayloads.add(payload);
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable throwable) {
        }
    }
}
