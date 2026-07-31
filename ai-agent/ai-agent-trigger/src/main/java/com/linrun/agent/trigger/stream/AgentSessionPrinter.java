package com.linrun.agent.trigger.stream;

import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.printer.ReplayFrameSink;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/** Sends canonical typed events and replays stored canonical JSON unchanged. */
@Slf4j
public class AgentSessionPrinter implements Printer, ReplayFrameSink {

    private final AgentMessageStream stream;
    private final AgentRequest request;
    private final AgentStreamEventStore eventStore;

    public AgentSessionPrinter(AgentMessageStream stream, AgentRequest request) {
        this(stream, request, null);
    }

    public AgentSessionPrinter(AgentMessageStream stream, AgentRequest request,
                               AgentStreamEventStore eventStore) {
        this.stream = stream;
        this.request = request;
        this.eventStore = eventStore;
    }

    @Override
    public void send(AgentStreamEvent event) {
        if (event == null) {
            return;
        }
        long eventSequence = -1L;
        if (eventStore != null) {
            eventSequence = eventStore.appendAndGetSequence(
                    request.getRequestId(), event.type(), JsonUtils.toJson(event));
        }
        try {
            if (eventSequence > 0L) {
                stream.send(event.type(), String.valueOf(eventSequence), event);
            } else {
                stream.send(event.type(), event);
            }
        } catch (Exception error) {
            log.error("{} canonical stream send failed type={} errorType={}",
                    request == null ? null : request.getRequestId(), event.type(),
                    error.getClass().getSimpleName());
            if (event instanceof AgentStreamEvent.Complete || event instanceof AgentStreamEvent.Error) {
                throw new IllegalStateException("Failed to send terminal Agent event: " + event.type(), error);
            }
        }
    }

    @Override
    public void close() {
        stream.complete();
    }

    @Override
    public boolean isAborted() {
        return stream != null && stream.isAborted();
    }

    @Override
    public void sendReplayFrame(GptProcessResult frame) {
        try {
            stream.send(frame);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to send projected Agent replay frame", error);
        }
    }

    @Override
    public void sendCanonicalReplay(String eventType, String eventJson) {
        try {
            stream.send(eventType, JsonUtils.parseTree(eventJson));
        } catch (Exception error) {
            throw new IllegalStateException("Failed to replay canonical Agent event: " + eventType, error);
        }
    }
}
