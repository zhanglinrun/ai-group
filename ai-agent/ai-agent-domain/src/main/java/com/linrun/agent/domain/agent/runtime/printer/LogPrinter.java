package com.linrun.agent.domain.agent.runtime.printer;


import lombok.extern.slf4j.Slf4j;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;

@Slf4j
public class LogPrinter implements Printer {
    private final AgentRequest request;

    public LogPrinter(AgentRequest request) {
        this.request = request;
    }

    @Override
    public void send(AgentStreamEvent event) {
        log.info("{} agent event sent type={} eventClass={} eventChars={}",
                request.getRequestId(), event == null ? null : event.type(),
                event == null ? "null" : event.getClass().getSimpleName(),
                event == null ? 0 : String.valueOf(event).length());
    }

    @Override
    public void close() {
    }

}
