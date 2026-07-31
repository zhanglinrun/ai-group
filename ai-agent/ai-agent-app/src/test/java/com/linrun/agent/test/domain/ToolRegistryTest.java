package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.harness.ToolPermissionMetadata;
import com.linrun.agent.domain.agent.runtime.harness.ToolRiskLevel;
import com.linrun.agent.domain.agent.runtime.harness.ToolSideEffect;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.ToolSearchTool;
import com.linrun.agent.domain.agent.runtime.tool.registry.ToolDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.registry.ToolRegistry;
import com.linrun.agent.domain.agent.runtime.tool.registry.ToolRetryPolicy;
import com.linrun.agent.domain.agent.runtime.tool.registry.ToolSource;

import java.util.Map;

/** P60 runtime registry contracts: metadata is complete, deterministic and fail-closed. */
public class ToolRegistryTest {

    @Test
    public void shouldDescribeCoreInternalAndMcpToolsWithPolicyMetadata() {
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(new RetriableReadTool());
        catalog.addTool(new ToolSearchTool());
        catalog.addMcpTool(McpToolInfo.builder()
                .mcpId("calendar")
                .name("create_event")
                .exposedName("mcp__calendar__create_event")
                .parameters("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"eventId\":{\"type\":\"string\"}},\"required\":[\"eventId\"]}")
                .riskLevel(ToolRiskLevel.HIGH)
                .sideEffect(ToolSideEffect.MUTATING)
                .build());

        ToolRegistry registry = catalog.registry();
        ToolDescriptor core = registry.find("read_snapshot").orElseThrow();
        ToolDescriptor internal = registry.find("tool_search").orElseThrow();
        ToolDescriptor mcp = registry.find("mcp__calendar__create_event").orElseThrow();

        Assert.assertEquals(ToolSource.CORE, core.source());
        Assert.assertEquals(ToolRetryPolicy.TRANSIENT_ONLY, core.retryPolicy());
        Assert.assertTrue(core.concurrencySafe());
        Assert.assertFalse(core.approvalRequired());
        Assert.assertTrue(core.version().startsWith("core-"));
        Assert.assertTrue(core.definitionHash().startsWith("sha256:"));

        Assert.assertEquals(ToolSource.INTERNAL, internal.source());
        Assert.assertFalse(internal.concurrencySafe());

        Assert.assertEquals(ToolSource.MCP, mcp.source());
        Assert.assertTrue(mcp.approvalRequired());
        Assert.assertEquals(ToolSideEffect.MUTATING, mcp.sideEffect());
        Assert.assertTrue(mcp.inputSchema().contains("title"));
        Assert.assertTrue(mcp.outputSchema().contains("eventId"));
    }

    @Test
    public void shouldChangeDefinitionHashWhenMcpSchemaChanges() {
        McpToolInfo tool = McpToolInfo.builder()
                .mcpId("inventory")
                .name("read_stock")
                .exposedName("mcp__inventory__read_stock")
                .parameters("{\"type\":\"object\"}")
                .outputSchema("{\"type\":\"object\"}")
                .build();
        String first = tool.definitionHash();

        tool.setOutputSchema("{\"type\":\"object\",\"properties\":{\"quantity\":{\"type\":\"integer\"}}}");

        Assert.assertNotEquals(first, tool.definitionHash());
    }

    private static final class RetriableReadTool implements BaseTool {

        @Override
        public String getName() {
            return "read_snapshot";
        }

        @Override
        public String getDescription() {
            return "read current snapshot";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Object execute(Object input) {
            return "ok";
        }

        @Override
        public ToolPermissionMetadata permissionMetadata() {
            return ToolPermissionMetadata.readOnly();
        }

        @Override
        public boolean isRetryable() {
            return true;
        }

        @Override
        public boolean isConcurrencySafe(Object input) {
            return true;
        }
    }
}
