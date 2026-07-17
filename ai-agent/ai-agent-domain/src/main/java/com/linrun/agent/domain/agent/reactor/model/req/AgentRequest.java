package com.linrun.agent.domain.agent.reactor.model.req;


import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;

import java.util.List;

/**
 * Assistant请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {
    private String requestId;
    /**
     * 会话ID，用于多轮对话上下文复用
     */
    private String sessionId;
    /**
     * 登录用户 ID（ownerId = userId，由 Gateway 注入）。
     */
    private String ownerId;
    private String erp;
    private String query;
    /** 追加 outputStyle 指令前的用户原始问题。 */
    private String originalQuery;
    private Integer agentType;
    /** AUTO, STANDARD or DEEP. */
    private String executionMode;
    private String basePrompt;
    /**
     * 会话级历史摘要文本
     */
    private String historyDialogue;
    private Boolean isStream;
    private List<Message> messages;
    /**
     * 恢复出的会话级稳定文件
     */
    private List<FileInformation> sessionFiles;
    private String outputStyle; // 交付物产出格式：html(网页模式）， docs(文档模式）， table(表格模式）
    private String aiAgentId;
    /** Role/client snapshot used to scope configured MCP exposure in the unified runtime. */
    private List<String> profileClientIds;
    private String resolvedRoleName;
    /**
     * 用户选择的模型 ID（可空）。贯穿到 AgentContext.modelIdOverride，供运行时按 modelId 覆盖模型。
     */
    private String modelId;
    /** false 时不向本轮 Agent 暴露搜索与网页抓取工具。 */
    private Boolean online;

    public AgentExecutionProfile resolveExecutionProfile() {
        return AgentExecutionProfile.resolve(executionMode);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
        /**
         * 结构化消息类型，区分 thought / tool_use / tool_result / artifact 等上下文块。
         */
        private String messageType;
        /**
         * 工具调用链，映射到内部 assistant tool_calls 语义。
         */
        private List<ToolCall> toolCalls;
        /**
         * 工具结果对应的 toolCallId，映射到内部 tool 消息。
         */
        private String toolCallId;
        /**
         * 稳定产物引用，供节点和工具链复用。
         */
        private List<JSONObject> artifactRefs;
        /**
         * 是否只保留摘要或引用，不直接内联正文。
         */
        private Boolean referenceOnly;
        private String commandCode;
        private List<FileInformation> uploadFile;
        private List<FileInformation> files;

    }
}
