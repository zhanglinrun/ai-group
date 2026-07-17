package com.linrun.agent.trigger.stream;

import com.alibaba.fastjson.JSON;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.printer.ReplayFrameSink;
import com.linrun.agent.domain.agent.runtime.util.StringUtil;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.response.AgentResponse;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于应用层输出端口的 Printer 适配器。
 * 统一复用既有 AgentResponse 协议，避免领域层直接依赖 SSE 实现。
 */
@Slf4j
@Setter
public class AgentSessionPrinter implements Printer, ReplayFrameSink {

    private final AgentMessageStream stream;
    private final AgentRequest request;
    public AgentSessionPrinter(AgentMessageStream stream, AgentRequest request) {
        this.stream = stream;
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
        try {
            if (Objects.isNull(messageId)) {
                messageId = StringUtil.getUUID();
            }

            log.debug("{} stream send type={}, messageClass={}, hasDigitalEmployee={}",
                    request.getRequestId(), messageType,
                    message == null ? "null" : message.getClass().getSimpleName(),
                    StringUtils.isNotBlank(digitalEmployee));

            boolean finish = "result".equals(messageType);
            Map<String, Object> resultMap = new HashMap<>();

            AgentResponse response = AgentResponse.builder()
                    .requestId(request.getRequestId())
                    .messageId(messageId)
                    .messageType(messageType)
                    .messageTime(String.valueOf(System.currentTimeMillis()))
                    .resultMap(resultMap)
                    .finish(finish)
                    .isFinal(isFinal)
                    .build();

            if (extraResultMap != null && !extraResultMap.isEmpty()) {
                resultMap.putAll(extraResultMap);
            }

            if (!StringUtils.isEmpty(digitalEmployee)) {
                response.setDigitalEmployee(digitalEmployee);
                resultMap.put("digitalEmployee", digitalEmployee);
            }

            switch (messageType) {
                case "tool_thought":
                    response.setToolThought((String) message);
                    break;
                case "tool_result":
                    response.setToolResult((AgentResponse.ToolResult) message);
                    break;
                case "tool_call":
                case "run_started":
                case "todo_snapshot":
                case "verification_started":
                case "verification_result":
                case "completion_blocked":
                case "phase_changed":
                case "run_finished":
                case "browser":
                case "code":
                case "html":
                case "markdown":
                case "ppt":
                case "file":
                case "knowledge":
                case "deep_search":
                case "data_analysis":
                    response.setResultMap(JSON.parseObject(JSON.toJSONString(message)));
                    break;
                case "agent_stream":
                    response.setResult((String) message);
                    break;
                case "result":
                    if (message instanceof String) {
                        response.setResult((String) message);
                    } else if (message instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> taskResult = (Map<String, Object>) message;
                        Object summary = taskResult.get("taskSummary");
                        response.setResultMap(taskResult);
                        response.setResult(summary != null ? summary.toString() : null);
                    } else {
                        Map<String, Object> taskResult = JSON.parseObject(JSON.toJSONString(message));
                        response.setResultMap(taskResult);
                        response.setResult(taskResult.get("taskSummary").toString());
                    }
                    break;
                default:
                    break;
            }

            if (finish) {
                applyTerminalMetadata(response, response.getResultMap());
            }

            stream.send(response);
        } catch (Exception e) {
            log.error("{} stream send failed messageType={} errorType={}",
                    request == null ? null : request.getRequestId(), messageType,
                    e.getClass().getSimpleName());
            if (isTerminalControlEvent(messageType)) {
                throw new IllegalStateException("Failed to send terminal Agent event: " + messageType, e);
            }
        }
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

    private void applyTerminalMetadata(AgentResponse response, Map<String, Object> resultMap) {
        String status = firstNonBlank(resultMap, "runStatus", "status");
        if (StringUtils.isBlank(status)) {
            status = "SUCCESS";
        }
        String errorCode = firstNonBlank(resultMap, "errorCode");
        String errorMessage = firstNonBlank(resultMap, "errorMessage", "errorMsg");
        response.setStatus(status);
        response.setErrorCode(errorCode);
        response.setErrorMessage(errorMessage);
        response.setErrorMsg(errorMessage);
    }

    private String firstNonBlank(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private boolean isTerminalControlEvent(String messageType) {
        return "run_finished".equals(messageType) || "result".equals(messageType);
    }
}
