package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.replay.DialogueRunReplayService;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.printer.ReplayFrameSink;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class StandardCheckpointRecoveryIT {

    @Test
    public void shouldReplayPersistedStandardBoundariesWithoutReenteringExecution() {
        AgentStreamEventStore store = new AgentStreamEventStore() {
            @Override
            public void append(String requestId, String eventType, String eventJson) {
            }

            @Override
            public List<StoredStreamEvent> findByRequestId(String requestId) {
                return List.of(
                        new StoredStreamEvent(11L, "agent_start", "{\"type\":\"agent_start\"}"),
                        new StoredStreamEvent(12L, "tool_end", "{\"type\":\"tool_end\"}"),
                        new StoredStreamEvent(13L, "complete", "{\"type\":\"complete\"}"));
            }
        };
        RecordingReplayPrinter printer = new RecordingReplayPrinter();
        DialogueRunReplayService replay = new DialogueRunReplayService(null, null, store);

        String summary = replay.replay(printer, DialogueRunClaim.builder()
                .requestId("request-recovery").finalSummaryText("persisted summary").build());

        Assert.assertEquals("persisted summary", summary);
        Assert.assertEquals(List.of("agent_start", "tool_end", "complete"), printer.eventTypes);
    }

    private static final class RecordingReplayPrinter implements Printer, ReplayFrameSink {
        private final List<String> eventTypes = new ArrayList<>();

        @Override
        public void send(com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent event) {
            Assert.fail("checkpoint recovery must not execute fallback runtime events");
        }

        @Override
        public void close() {
        }

        @Override
        public void sendReplayFrame(GptProcessResult frame) {
            Assert.fail("canonical checkpoint stream should be replayed directly");
        }

        @Override
        public void sendCanonicalReplay(String eventType, String eventJson) {
            eventTypes.add(eventType);
        }
    }
}
