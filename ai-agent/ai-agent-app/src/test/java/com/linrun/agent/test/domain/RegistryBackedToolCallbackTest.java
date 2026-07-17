package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.RegistryBackedToolCallback;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/** Direct MCP registry callback contract used by the unified Agent Loop. */
public class RegistryBackedToolCallbackTest {

    @Test
    public void shouldReturnRegistryObservationWithOrWithoutToolContext() {
        McpRegistry registry = Mockito.mock(McpRegistry.class);
        Mockito.when(registry.executeTool("search-mcp", "remote_search", "{\"q\":\"agent\"}"))
                .thenReturn(ToolResultPayload.text("search result"));
        RegistryBackedToolCallback callback = callback(registry);

        Assert.assertEquals("search result", callback.call("{\"q\":\"agent\"}"));
        Assert.assertEquals("search result", callback.call(
                "{\"q\":\"agent\"}", new ToolContext(Map.of("ignored", true))));

        Mockito.verify(registry, Mockito.times(2))
                .executeTool("search-mcp", "remote_search", "{\"q\":\"agent\"}");
    }

    @Test
    public void shouldReturnTypedFailureObservationFromRegistry() {
        McpRegistry registry = Mockito.mock(McpRegistry.class);
        Mockito.when(registry.executeTool("search-mcp", "remote_search", "{}"))
                .thenReturn(ToolResultPayload.failure(
                        "Toolremote_search Error.",
                        "Toolremote_search Error.",
                        null,
                        "connection reset"));

        Assert.assertEquals("Toolremote_search Error.", callback(registry).call("{}"));
    }

    private RegistryBackedToolCallback callback(McpRegistry registry) {
        return new RegistryBackedToolCallback(registry, McpToolInfo.builder()
                .mcpId("search-mcp")
                .name("remote_search")
                .desc("search")
                .parameters("{}")
                .build());
    }
}
