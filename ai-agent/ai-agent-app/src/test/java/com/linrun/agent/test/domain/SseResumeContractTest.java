package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.trigger.stream.AgentSessionPrinter;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SseResumeContractTest {

    @Test
    public void shouldUseDurableEventSequencesForLiveSseIdsAndCursorReplay() {
        SequencedEventStore store = new SequencedEventStore();
        CursorRecordingStream stream = new CursorRecordingStream();
        AgentSessionPrinter printer = new AgentSessionPrinter(
                stream, AgentRequest.builder().requestId("request-cursor").build(), store);

        printer.send(new AgentStreamEvent.Text("request-cursor", "first"));
        printer.send(new AgentStreamEvent.Text("request-cursor", "second"));

        Assert.assertEquals(List.of("1", "2"), stream.eventIds);
        Assert.assertEquals(List.of("text", "text"), stream.eventNames);
        Assert.assertEquals(1, store.findByRequestIdAfter("request-cursor", 1L).size());
        Assert.assertEquals(2L, store.findByRequestIdAfter("request-cursor", 1L).getFirst().sequence());
    }

    private static final class SequencedEventStore implements AgentStreamEventStore {
        private final List<StoredStreamEvent> events = new ArrayList<>();

        @Override
        public void append(String requestId, String eventType, String eventJson) {
            appendAndGetSequence(requestId, eventType, eventJson);
        }

        @Override
        public long appendAndGetSequence(String requestId, String eventType, String eventJson) {
            long sequence = events.size() + 1L;
            events.add(new StoredStreamEvent(sequence, eventType, eventJson));
            return sequence;
        }

        @Override
        public List<StoredStreamEvent> findByRequestId(String requestId) {
            return List.copyOf(events);
        }
    }

    private static final class CursorRecordingStream implements AgentMessageStream {
        private final List<String> eventNames = new ArrayList<>();
        private final List<String> eventIds = new ArrayList<>();

        @Override
        public void send(Object payload) {
        }

        @Override
        public void send(String eventName, String eventId, Object payload) {
            eventNames.add(eventName);
            eventIds.add(eventId);
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable throwable) {
        }
    }
}
