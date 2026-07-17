package com.linrun.agent.domain.agent.reactor.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Assistant返回
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    private String requestId;
    private String messageId;
    private Boolean isFinal;
    private String messageType;
    private String digitalEmployee;
    private String messageTime;
    private String toolThought;
    private ToolResult toolResult;
    private Map<String, Object> resultMap;
    private String result;
    private Boolean finish;
    /** Authoritative run terminal status (SUCCESS / FAILED / STOPPED / TIMEOUT). */
    private String status;
    private String errorCode;
    private String errorMessage;
    /** Compatibility alias consumed by the existing SSE envelope. */
    private String errorMsg;
    private Map<String, String> ext;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResult {
        private String toolName;
        private Map<String, Object> toolParam;
        private String toolResult;
        private String toolCallId;
    }

}
