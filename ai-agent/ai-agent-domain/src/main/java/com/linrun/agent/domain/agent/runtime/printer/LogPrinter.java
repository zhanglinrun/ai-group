package com.linrun.agent.domain.agent.runtime.printer;


import lombok.extern.slf4j.Slf4j;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;

import java.util.Map;

@Slf4j
public class LogPrinter implements Printer {
    private final AgentRequest request;

    public LogPrinter(AgentRequest request) {
        this.request = request;
    }

    @Override
    public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
        send(messageId, messageType, message, null, digitalEmployee, isFinal);
    }

    @Override
    public void send(String messageId,
                     String messageType,
                     Object message,
                     Map<String, Object> extraResultMap,
                     String digitalEmployee,
                     Boolean isFinal) {
        log.info("{} agent message sent messageId={} messageType={} messageClass={} messageChars={} extraKeyCount={} hasDigitalEmployee={} isFinal={}",
                request.getRequestId(),
                messageId,
                messageType,
                message == null ? "null" : message.getClass().getSimpleName(),
                message == null ? 0 : String.valueOf(message).length(),
                extraResultMap == null ? 0 : extraResultMap.size(),
                digitalEmployee != null && !digitalEmployee.isBlank(),
                isFinal);
    }

    @Override
    public void send(String messageType, Object message, String digitalEmployee) {
        send(null, messageType, message, digitalEmployee, true);
    }

    @Override
    public void send(String messageType, Object message) {
        send(null, messageType, message, null, true);
    }

    @Override
    public void send(String messageId, String messageType, Object message, Boolean isFinal) {
        send(messageId, messageType, message, (String) null, isFinal);
    }

    @Override
    public void sendWithResultMap(String messageId,
                                  String messageType,
                                  Object message,
                                  Map<String, Object> extraResultMap,
                                  Boolean isFinal) {
        send(messageId, messageType, message, extraResultMap, null, isFinal);
    }

    @Override
    public void sendWithResultMap(String messageType, Object message, Map<String, Object> extraResultMap) {
        send(null, messageType, message, extraResultMap, null, true);
    }

    @Override
    public void close() {
    }

}
