package com.linrun.agent.test.domain;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpClientRuntime;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;

import java.util.List;
import java.util.Map;

public class McpRegistryFailureContractTest {

    @Test
    public void shouldReturnTypedFailureWhenTransportThrows() {
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        Mockito.when(client.callTool(Mockito.any(McpSchema.CallToolRequest.class)))
                .thenThrow(new IllegalStateException("connection reset"));
        McpRegistry registry = registryWithClient(client);

        ToolResultPayload payload = registry.executeTool("test-mcp", "remote_search", Map.of("query", "agent"));

        Assert.assertTrue(payload.getFailed());
        Assert.assertEquals("Toolremote_search Error.", payload.getLlmObservation());
        Assert.assertEquals("connection reset", payload.getErrorMsg());
    }

    @Test
    public void shouldReturnTypedFailureWhenMcpMarksResultAsError() {
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        Mockito.when(client.callTool(Mockito.any(McpSchema.CallToolRequest.class)))
                .thenReturn(McpSchema.CallToolResult.builder()
                        .addTextContent("permission denied")
                        .isError(true)
                        .build());
        McpRegistry registry = registryWithClient(client);

        ToolResultPayload payload = registry.executeTool("test-mcp", "remote_search", Map.of());

        Assert.assertTrue(payload.getFailed());
        Assert.assertTrue(payload.getLlmObservation().contains("permission denied"));
        Assert.assertEquals("permission denied", payload.getErrorMsg());
    }

    @Test
    public void shouldReturnTypedFailureWhenMcpReturnsNoUsableContent() {
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        Mockito.when(client.callTool(Mockito.any(McpSchema.CallToolRequest.class)))
                .thenReturn(McpSchema.CallToolResult.builder()
                        .addTextContent("   ")
                        .isError(false)
                        .build());
        McpRegistry registry = registryWithClient(client);

        ToolResultPayload payload = registry.executeTool("test-mcp", "remote_search", Map.of());

        Assert.assertTrue(payload.getFailed());
        Assert.assertEquals("Toolremote_search Error.", payload.getLlmObservation());
        Assert.assertEquals("MCP returned no usable content", payload.getErrorMsg());
    }

    @Test
    public void shouldReturnTypedFailureWhenMcpReturnsNullResult() {
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        Mockito.when(client.callTool(Mockito.any(McpSchema.CallToolRequest.class)))
                .thenReturn(null);
        McpRegistry registry = registryWithClient(client);

        ToolResultPayload payload = registry.executeTool("test-mcp", "remote_search", Map.of());

        Assert.assertTrue(payload.getFailed());
        Assert.assertEquals("Toolremote_search Error.", payload.getLlmObservation());
        Assert.assertEquals("MCP returned a null result", payload.getErrorMsg());
    }

    @Test
    public void shouldKeepExistingSuccessfulTextObservation() {
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        Mockito.when(client.callTool(Mockito.any(McpSchema.CallToolRequest.class)))
                .thenReturn(McpSchema.CallToolResult.builder()
                        .addTextContent("search result")
                        .isError(false)
                        .build());
        McpRegistry registry = registryWithClient(client);

        ToolResultPayload payload = registry.executeTool("test-mcp", "remote_search", Map.of());

        Assert.assertFalse(payload.getFailed());
        Assert.assertEquals("search result", payload.getToolResult());
        Assert.assertEquals("search result", payload.getLlmObservation());
        Assert.assertNull(payload.getErrorMsg());
    }

    @SuppressWarnings("unchecked")
    private McpRegistry registryWithClient(McpSyncClient client) {
        McpRegistry registry = new McpRegistry();
        McpServerDescriptor descriptor = McpServerDescriptor.builder()
                .mcpId("test-mcp")
                .serverKey("test-mcp")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_SSE)
                .serverUrl("https://mcp.test/sse")
                .build();
        McpClientRuntime runtime = McpClientRuntime.builder()
                .descriptor(descriptor)
                .syncClient(client)
                .build();

        Map<String, McpClientRuntime> runtimeCache =
                (Map<String, McpClientRuntime>) ReflectionTestUtils.getField(registry, "runtimeCache");
        Map<String, List<McpToolInfo>> toolCache =
                (Map<String, List<McpToolInfo>>) ReflectionTestUtils.getField(registry, "toolCache");
        Assert.assertNotNull(runtimeCache);
        Assert.assertNotNull(toolCache);
        runtimeCache.put("test-mcp", runtime);
        toolCache.put("test-mcp", List.of());
        return registry;
    }
}
