package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.runtime.dto.tool.McpToolInfo;
import org.wwz.ai.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import org.wwz.ai.domain.agent.runtime.tool.mcp.runtime.RegistryBackedToolCallback;
import org.wwz.ai.domain.agent.runtime.tool.mcp.runtime.WorkflowToolTraceContext;

import java.util.List;
import java.util.Map;

public class WorkflowToolTraceContextTest {

    @Test
    public void shouldRecordOneWorkflowMcpInvocationFromToolContext() {
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.createToolInvocations(Mockito.any()))
                .thenReturn(Map.of("call-quota-1", 901L));
        WorkflowToolTraceContext trace = new WorkflowToolTraceContext(
                recorder, 361L, "req-workflow-mcp", "session-workflow-mcp", 820L, "workflow", 1);

        String arguments = "{\"input_tokens\":1000}";
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-quota-1", "function", "utility_estimate_llm_quota", arguments)))
                .build();
        ToolContext toolContext = new ToolContext(Map.of(
                WorkflowToolTraceContext.CONTEXT_KEY, trace,
                ToolContext.TOOL_CALL_HISTORY, List.of(assistantMessage)
        ));

        McpRegistry registry = Mockito.mock(McpRegistry.class);
        Mockito.when(registry.executeTool("agent-utility", "utility_estimate_llm_quota", arguments))
                .thenReturn("{\"ok\":true,\"actual_microcredits\":8000}");
        RegistryBackedToolCallback callback = new RegistryBackedToolCallback(registry, McpToolInfo.builder()
                .mcpId("agent-utility")
                .name("utility_estimate_llm_quota")
                .desc("quota")
                .parameters("{}")
                .build());

        String result = callback.call(arguments, toolContext);

        Assert.assertEquals("{\"ok\":true,\"actual_microcredits\":8000}", result);
        Assert.assertEquals(1, trace.getCallCount());
        ArgumentCaptor<ToolInvocationBatchStartRecord> startCaptor =
                ArgumentCaptor.forClass(ToolInvocationBatchStartRecord.class);
        Mockito.verify(recorder).createToolInvocations(startCaptor.capture());
        ToolInvocationBatchStartRecord.Item item = startCaptor.getValue().getItems().get(0);
        Assert.assertEquals("call-quota-1", item.getToolCallId());
        Assert.assertEquals("utility_estimate_llm_quota", item.getToolName());
        Assert.assertEquals(ExecutionLedgerConstants.TOOL_PROVIDER_MCP, item.getToolProvider());

        ArgumentCaptor<ToolInvocationFinishRecord> finishCaptor =
                ArgumentCaptor.forClass(ToolInvocationFinishRecord.class);
        Mockito.verify(recorder).finishToolInvocation(finishCaptor.capture());
        Assert.assertEquals(Long.valueOf(901L), finishCaptor.getValue().getToolInvocationId());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS),
                finishCaptor.getValue().getStatus());
        Assert.assertTrue(finishCaptor.getValue().getLlmObservation().contains("8000"));
    }

    @Test
    public void shouldNotRecordWhenWorkflowTraceIsAbsent() {
        McpRegistry registry = Mockito.mock(McpRegistry.class);
        Mockito.when(registry.executeTool(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn("ok");
        RegistryBackedToolCallback callback = new RegistryBackedToolCallback(registry, McpToolInfo.builder()
                .mcpId("agent-utility")
                .name("utility_estimate_llm_quota")
                .desc("quota")
                .parameters("{}")
                .build());

        Assert.assertEquals("ok", callback.call("{}", new ToolContext(Map.of())));
        Mockito.verify(registry).executeTool("agent-utility", "utility_estimate_llm_quota", "{}");
    }
}
