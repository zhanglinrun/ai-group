package com.linrun.agent.domain.agent.runtime.stream;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Agent 流式事件协议（sealed interface）。
 *
 * <p>用 Java 21 sealed interface 把 SSE 事件类型收口为 11 种，编译期穷尽检查：
 * 前端渲染 switch 时新增事件类型会编译报错，避免遗漏渲染分支。
 *
 * <p>事件生命周期：
 * <pre>
 * AgentStart → (Thinking | Text | ToolStart → ToolEnd | TodoProgress | StageOutput | Paused → ResumeStart)* → Complete | Error
 * </pre>
 */
public sealed interface AgentStreamEvent
        permits AgentStreamEvent.AgentStart,
        AgentStreamEvent.Thinking,
        AgentStreamEvent.Text,
        AgentStreamEvent.ToolStart,
        AgentStreamEvent.ToolEnd,
        AgentStreamEvent.TodoProgress,
        AgentStreamEvent.Paused,
        AgentStreamEvent.ResumeStart,
        AgentStreamEvent.StageOutput,
        AgentStreamEvent.Error,
        AgentStreamEvent.Complete {

    /** 事件类型名，同时写入 SSE event 字段和 canonical JSON data。 */
    @JsonProperty("type")
    String type();

    /** Agent 执行开始 */
    record AgentStart(String runId, String ownerId, String conversationId,
                      String agentName, String modelId) implements AgentStreamEvent {
        @Override public String type() { return "agent_start"; }
    }

    /** 模型思考/推理（折叠展示） */
    record Thinking(String runId, String content) implements AgentStreamEvent {
        @Override public String type() { return "thinking"; }
    }

    /** 文本输出块（增量流式） */
    record Text(String runId, String delta) implements AgentStreamEvent {
        @Override public String type() { return "text"; }
    }

    /** 工具调用开始 */
    record ToolStart(String runId, String toolCallId, String toolName,
                     Object argumentsPreview) implements AgentStreamEvent {
        @Override public String type() { return "tool_start"; }
    }

    /** 工具调用结束 */
    record ToolEnd(String runId, String toolCallId, String toolName,
                   Object resultPreview, boolean success, long durationMillis) implements AgentStreamEvent {
        @Override public String type() { return "tool_end"; }
    }

    /** Todo/任务进度更新。 */
    record TodoProgress(String runId, List<Map<String, Object>> items) implements AgentStreamEvent {
        @Override public String type() { return "todo_progress"; }
    }

    /** HITL 暂停（等待用户审批） */
    record Paused(String runId, String approvalId, String toolCallId, String toolName,
                  Object argumentsPreview, long estimatedMicrocredits, String expiresAt) implements AgentStreamEvent {
        @Override public String type() { return "paused"; }
    }

    /** 审批通过后恢复执行 */
    record ResumeStart(String runId, String approvalId, String toolCallId, String decision) implements AgentStreamEvent {
        @Override public String type() { return "resume_start"; }
    }

    /** 工具产物或结构化阶段输出。 */
    record StageOutput(String runId, String toolCallId, String outputType,
                       Object payload, List<Map<String, Object>> artifactRefs,
                       boolean isFinal) implements AgentStreamEvent {
        @Override public String type() { return "stage_output"; }
    }

    /** 错误事件 */
    record Error(String runId, String code, String message) implements AgentStreamEvent {
        @Override public String type() { return "error"; }
    }

    /** 执行完成 */
    record Complete(String runId, String summary, long totalDurationMillis,
                    long microcreditsConsumed) implements AgentStreamEvent {
        @Override public String type() { return "complete"; }
    }
}
