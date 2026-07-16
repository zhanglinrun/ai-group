package org.wwz.ai.domain.agent.runtime.tool.mcp.runtime;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request-scoped trace passed through Spring AI ToolContext for workflow MCP calls.
 */
public final class WorkflowToolTraceContext {

    public static final String CONTEXT_KEY = WorkflowToolTraceContext.class.getName();

    private final AgentExecutionRecorder recorder;
    private final Long runId;
    private final String requestId;
    private final String sessionId;
    private final Long llmInvocationId;
    private final String agentName;
    private final Integer stepNo;
    private final AtomicInteger dispatchSequence = new AtomicInteger();
    private final AtomicInteger callCount = new AtomicInteger();
    private final Set<String> claimedToolCallIds = ConcurrentHashMap.newKeySet();

    public WorkflowToolTraceContext(AgentExecutionRecorder recorder,
                                    Long runId,
                                    String requestId,
                                    String sessionId,
                                    Long llmInvocationId,
                                    String agentName,
                                    Integer stepNo) {
        this.recorder = recorder;
        this.runId = runId;
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.llmInvocationId = llmInvocationId;
        this.agentName = agentName;
        this.stepNo = stepNo;
    }

    public static WorkflowToolTraceContext from(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(CONTEXT_KEY);
        return value instanceof WorkflowToolTraceContext trace ? trace : null;
    }

    public TraceInvocation begin(String toolName, String inputJson, ToolContext toolContext) {
        int dispatchIndex = dispatchSequence.incrementAndGet();
        callCount.incrementAndGet();
        String toolCallId = resolveToolCallId(toolName, inputJson, toolContext, dispatchIndex);
        Long toolInvocationId = null;
        if (recorder != null && runId != null) {
            Map<String, Long> mapping = recorder.createToolInvocations(ToolInvocationBatchStartRecord.builder()
                    .runId(runId)
                    .requestId(requestId)
                    .llmInvocationId(llmInvocationId)
                    .agentName(agentName)
                    .stepNo(stepNo)
                    .items(List.of(ToolInvocationBatchStartRecord.Item.builder()
                            .toolCallId(toolCallId)
                            .dispatchIndex(dispatchIndex)
                            .toolName(toolName)
                            .toolProvider(ExecutionLedgerConstants.TOOL_PROVIDER_MCP)
                            .inputJson(StringUtils.defaultIfBlank(inputJson, "{}"))
                            .startedAt(LocalDateTime.now())
                            .build()))
                    .build());
            toolInvocationId = mapping.get(toolCallId);
        }
        return new TraceInvocation(toolInvocationId, toolCallId, toolName);
    }

    public void finishSuccess(TraceInvocation invocation, String observation) {
        finish(invocation, ExecutionLedgerConstants.STATUS_SUCCESS, observation, null);
    }

    public void finishFailure(TraceInvocation invocation, RuntimeException error) {
        finish(invocation, ExecutionLedgerConstants.resolveFailureStatus(error), null,
                error == null ? "MCP tool execution failed" : error.getMessage());
    }

    public int getCallCount() {
        return callCount.get();
    }

    private void finish(TraceInvocation invocation, int status, String observation, String errorMsg) {
        if (recorder == null || invocation == null || invocation.toolInvocationId() == null) {
            return;
        }
        recorder.finishToolInvocation(ToolInvocationFinishRecord.builder()
                .toolInvocationId(invocation.toolInvocationId())
                .runId(runId)
                .requestId(requestId)
                .sessionId(sessionId)
                .toolCallId(invocation.toolCallId())
                .toolName(invocation.toolName())
                .status(status)
                .llmObservation(observation)
                .errorMsg(errorMsg)
                .finishedAt(LocalDateTime.now())
                .build());
    }

    private String resolveToolCallId(String toolName,
                                     String inputJson,
                                     ToolContext toolContext,
                                     int dispatchIndex) {
        if (toolContext != null) {
            List<Message> history = toolContext.getToolCallHistory();
            if (history == null) {
                history = List.of();
            }
            for (int messageIndex = history.size() - 1; messageIndex >= 0; messageIndex--) {
                Message message = history.get(messageIndex);
                if (!(message instanceof AssistantMessage assistantMessage)) {
                    continue;
                }
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                if (toolCalls == null) {
                    continue;
                }
                for (int callIndex = toolCalls.size() - 1; callIndex >= 0; callIndex--) {
                    AssistantMessage.ToolCall toolCall = toolCalls.get(callIndex);
                    if (!StringUtils.equals(toolName, toolCall.name())
                            || StringUtils.isBlank(toolCall.id())
                            || !argumentsMatch(inputJson, toolCall.arguments())) {
                        continue;
                    }
                    if (claimedToolCallIds.add(toolCall.id())) {
                        return toolCall.id();
                    }
                }
            }
        }
        String generated = "workflow-mcp-" + StringUtils.defaultIfBlank(requestId, "unknown") + "-" + dispatchIndex;
        claimedToolCallIds.add(generated);
        return generated;
    }

    private boolean argumentsMatch(String callbackInput, String toolCallArguments) {
        if (StringUtils.isBlank(callbackInput) || StringUtils.isBlank(toolCallArguments)) {
            return true;
        }
        return StringUtils.deleteWhitespace(callbackInput)
                .equals(StringUtils.deleteWhitespace(toolCallArguments));
    }

    public record TraceInvocation(Long toolInvocationId, String toolCallId, String toolName) {
    }
}
