package com.linrun.agent.domain.agent.runtime.loop;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Model boundary used by the Agent Loop. */
public interface ModelGateway {

    ModelTurnResponse complete(ModelTurnRequest request) throws Exception;

    String functionCallType();

    record ModelTurnRequest(AgentContext context,
                            List<Message> messages,
                            Message systemMessage,
                            ToolCollection tools,
                            ToolChoice toolChoice,
                            Double temperature,
                            boolean stream,
                            boolean pushToClient,
                            int timeoutSeconds,
                            Duration callLimit) {
        public ModelTurnRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? new ToolCollection() : tools;
            timeoutSeconds = Math.max(1, timeoutSeconds);
            callLimit = callLimit == null ? Duration.ofSeconds(timeoutSeconds) : callLimit;
        }
    }

    record ModelTurnResponse(String content,
                             List<ToolCall> toolCalls,
                             ModelFinishReason finishReason,
                             String rawFinishReason) {
        public ModelTurnResponse {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            rawFinishReason = rawFinishReason == null ? "" : rawFinishReason.trim();
            finishReason = finishReason == null
                    ? ModelFinishReason.fromProvider(rawFinishReason)
                    : finishReason;
        }

        public ModelTurnResponse(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, ModelFinishReason.NORMAL, "");
        }

        public static ModelTurnResponse fromProvider(String content,
                                                     List<ToolCall> toolCalls,
                                                     String rawFinishReason) {
            return new ModelTurnResponse(
                    content,
                    toolCalls,
                    ModelFinishReason.fromProvider(rawFinishReason),
                    rawFinishReason);
        }
    }

    enum ModelFinishReason {
        NORMAL,
        MAX_TOKENS,
        REFUSAL,
        CONTENT_FILTER,
        UNKNOWN;

        public boolean permitsAgentContinuation() {
            return this == NORMAL;
        }

        static ModelFinishReason fromProvider(String rawFinishReason) {
            String normalized = rawFinishReason == null
                    ? ""
                    : rawFinishReason.trim().toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if (normalized.isEmpty()
                    || "stop".equals(normalized)
                    || "end_turn".equals(normalized)
                    || "tool_calls".equals(normalized)
                    || "tool_use".equals(normalized)
                    || "function_call".equals(normalized)) {
                return NORMAL;
            }
            if ("length".equals(normalized)
                    || "max_tokens".equals(normalized)
                    || "max_output_tokens".equals(normalized)) {
                return MAX_TOKENS;
            }
            if ("refusal".equals(normalized) || "refused".equals(normalized)) {
                return REFUSAL;
            }
            if ("content_filter".equals(normalized)
                    || "content_filtered".equals(normalized)
                    || "safety".equals(normalized)) {
                return CONTENT_FILTER;
            }
            return UNKNOWN;
        }
    }
}
