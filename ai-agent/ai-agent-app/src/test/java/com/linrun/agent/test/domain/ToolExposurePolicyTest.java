package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.Memory;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.ExecuteExtraTool;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ToolSearchTool;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.tool.exposure.ToolExposurePolicy;
import com.linrun.agent.domain.agent.runtime.loop.ContextPipeline;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ToolExposurePolicyTest {

    @Test
    public void shouldExposePlatformContextForStrongOrQualifiedPlatformSemantics() {
        ReactorConfig config = config("all", 8, 6);
        List<String> queries = List.of(
                "查看我的额度还剩多少",
                "有哪些会员套餐",
                "看看当前拼团活动",
                "查询我的订单",
                "本平台现在的价格是多少",
                "show my account balance");

        for (String query : queries) {
            ToolCollection catalog = platformCatalog();
            AgentContext context = context(config, catalog, query, "");
            ToolCollection exposed = ToolExposurePolicy.selectForTurn(catalog, context, config);
            Assert.assertTrue(query, exposed.getToolMap().containsKey(PlatformContextTool.NAME));
        }
    }

    @Test
    public void shouldHidePlatformContextForAmbiguousNonPlatformQuestions() {
        ReactorConfig config = config("all", 8, 6);
        List<String> queries = List.of(
                "比较 Codex、Claude Code、Cursor 的价格",
                "讲一下支付系统设计",
                "SQL order by 怎么优化",
                "explain account abstraction",
                "how to buy a domain name");

        for (String query : queries) {
            ToolCollection catalog = platformCatalog();
            AgentContext context = context(config, catalog, query, "");
            ToolCollection exposed = ToolExposurePolicy.selectForTurn(catalog, context, config);
            Assert.assertFalse(query, exposed.getToolMap().containsKey(PlatformContextTool.NAME));
        }
    }

    @Test
    public void explicitPlatformContextContractShouldOverrideSemanticPrefilter() {
        ReactorConfig config = config("all", 8, 6);
        ToolCollection catalog = platformCatalog();
        AgentContext context = context(config, catalog, "必须调用 platform_context", "");
        context.setToolInvocationContract(ToolInvocationContract.resolve(
                context.getQuery(), catalog.getToolMap().keySet()));

        ToolCollection exposed = ToolExposurePolicy.selectForTurn(catalog, context, config);

        Assert.assertTrue(exposed.getToolMap().containsKey(PlatformContextTool.NAME));
    }

    @Test
    public void shouldKeepStableProxySchemaAndDeferEveryMcpTool() {
        ReactorConfig config = config("filtered", 2, 2);
        ToolCollection catalog = catalog(10);
        AgentContext context = context(config, catalog, "查询团队日历事件", "读取日历中的下一场会议");

        ContextPipeline.PreparedModelTurn turn = new ContextPipeline().prepareTurn(
                context,
                config,
                new ContextPipeline.PromptState("system", "next"),
                new Memory(),
                catalog,
                1);
        ToolCollection exposed = turn.exposedTools();

        Assert.assertTrue(exposed.getToolMap().containsKey("deep_search"));
        Assert.assertTrue(exposed.getToolMap().containsKey("tool_search"));
        Assert.assertTrue(exposed.getToolMap().containsKey(ExecuteExtraTool.NAME));
        Assert.assertTrue(exposed.getMcpToolMap().isEmpty());
        Assert.assertFalse(exposed.getMcpToolMap().containsKey("mcp__server9__unrelated_tool_9"));
        Assert.assertTrue(context.getAgentRunState().getLatestDeferredToolCountValue() > 0);
    }

    @Test
    public void shouldInjectAtMostThreePinnedNativeSchemasOnNextTurn() {
        ReactorConfig config = config("filtered", 2, 1);
        ToolCollection catalog = catalog(10);
        AgentContext context = context(config, catalog, "完成业务任务", "继续执行");
        ToolCollection before = ToolExposurePolicy.selectForTurn(catalog, context, config);
        context.getAgentRunState().markToolsDiscovered(Map.of(
                "mcp__server1__unrelated_tool_1", catalog.getMcpTool("mcp__server1__unrelated_tool_1").definitionHash(),
                "mcp__server2__unrelated_tool_2", catalog.getMcpTool("mcp__server2__unrelated_tool_2").definitionHash(),
                "mcp__server3__unrelated_tool_3", catalog.getMcpTool("mcp__server3__unrelated_tool_3").definitionHash(),
                "mcp__server4__unrelated_tool_4", catalog.getMcpTool("mcp__server4__unrelated_tool_4").definitionHash()));

        ToolCollection after = ToolExposurePolicy.selectForTurn(catalog, context, config);

        Assert.assertTrue(before.getMcpToolMap().isEmpty());
        Assert.assertEquals(3, after.getMcpToolMap().size());
        Assert.assertTrue(after.getMcpToolMap().containsKey("mcp__server1__unrelated_tool_1"));
        Assert.assertTrue(after.getMcpToolMap().containsKey("mcp__server2__unrelated_tool_2"));
        Assert.assertTrue(after.getMcpToolMap().containsKey("mcp__server3__unrelated_tool_3"));
        Assert.assertFalse(after.getMcpToolMap().containsKey("mcp__server4__unrelated_tool_4"));
    }

    @Test
    public void toolSearchShouldDiscoverDeferredNativeTool() {
        ReactorConfig config = config("filtered", 2, 1);
        ToolCollection catalog = catalog(10);
        AgentContext context = context(config, catalog, "处理日历", "寻找查询会议的工具");
        ToolSearchTool tool = new ToolSearchTool();
        tool.setAgentContext(context);

        String result = String.valueOf(tool.execute(Map.of("query", "查询日历会议", "limit", 3)));

        Assert.assertTrue(result, result.contains("mcp__calendar__list_events"));
        Assert.assertTrue(result, result.contains("definition_hash"));
        Assert.assertTrue(result, result.contains("available_next_turn"));
        Assert.assertFalse(result, result.contains("input_schema"));
        Assert.assertTrue(context.getAgentRunState().discoveredToolNamesSnapshot()
                .contains("mcp__calendar__list_events"));
    }

    @Test
    public void shouldRejectDiscoveredSchemaWhenRegistryDefinitionChangesDuringRun() {
        ReactorConfig config = config("filtered", 2, 1);
        ToolCollection catalog = catalog(3);
        AgentContext context = context(config, catalog, "完成业务任务", "继续执行");
        McpToolInfo target = catalog.getMcpTool("mcp__calendar__list_events");
        context.getAgentRunState().markToolsDiscovered(Map.of(target.resolveExposedName(), target.definitionHash()));
        target.setParameters("{\"type\":\"object\",\"properties\":{\"changed\":{\"type\":\"string\"}}}");

        ToolCollection exposed = ToolExposurePolicy.selectForTurn(catalog, context, config);

        Assert.assertFalse(exposed.getMcpToolMap().containsKey(target.resolveExposedName()));
    }

    @Test
    public void shouldNamespaceRealMcpToolsAndKeepRemoteName() {
        McpToolInfo tool = McpToolInfo.builder()
                .mcpId("team calendar")
                .name("list/events")
                .exposedName(McpToolInfo.canonicalExposedName("team calendar", "list/events"))
                .build();

        Assert.assertEquals("list/events", tool.getName());
        Assert.assertEquals("mcp__team_calendar__list_events", tool.resolveExposedName());
    }

    @Test
    public void selectedViewShouldNotExecuteHiddenTool() {
        ToolCollection catalog = catalog(3);
        ToolCollection selected = catalog.selectedView(List.of("deep_search"));

        Assert.assertNull(selected.execute("mcp__calendar__list_events", Map.of()));
    }

    @Test
    public void shouldKeepProviderToolSetStableWhenTodoCurrentStepChanges() {
        ReactorConfig config = config("filtered", 1, 1);
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(new StubTool("deep_search", "联网搜索"));
        ToolSearchTool searchTool = new ToolSearchTool();
        catalog.addTool(searchTool);
        catalog.addTool(new ExecuteExtraTool());
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        catalog.addTool(todoWriteTool);
        catalog.addMcpTool(McpToolInfo.builder()
                .mcpId("calendar")
                .name("list_events")
                .exposedName("mcp__calendar__list_events")
                .desc("查询团队日历、会议和事件")
                .parameters("{}")
                .build());
        catalog.addMcpTool(McpToolInfo.builder()
                .mcpId("inventory")
                .name("query_stock")
                .exposedName("mcp__inventory__query_stock")
                .desc("查询仓库库存数量和商品存量")
                .parameters("{}")
                .build());
        AgentContext context = context(config, catalog, "完成业务任务", "");
        todoWriteTool.setAgentContext(context);

        todoWriteTool.execute(Map.of(
                "command", "create",
                "title", "业务处理",
                "steps", List.of("查询团队日历事件", "查询仓库库存数量")
        ));
        Assert.assertEquals("查询团队日历事件", context.getTask());
        ToolCollection calendarTurn = ToolExposurePolicy.selectForTurn(catalog, context, config);
        Assert.assertTrue(calendarTurn.getMcpToolMap().isEmpty());

        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed"
        ));
        Assert.assertEquals("查询仓库库存数量", context.getTask());
        ToolCollection inventoryTurn = ToolExposurePolicy.selectForTurn(catalog, context, config);
        Assert.assertEquals(calendarTurn.getToolMap().keySet(), inventoryTurn.getToolMap().keySet());
        Assert.assertEquals(calendarTurn.getMcpToolMap().keySet(), inventoryTurn.getMcpToolMap().keySet());
    }

    @Test
    public void exclusiveContractShouldExposeOnlyRequiredToolAndTodoControlTool() {
        ReactorConfig config = config("all", 8, 6);
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(new StubTool("required_tool", "required"));
        catalog.addTool(new StubTool("blocked_tool", "blocked"));
        catalog.addTool(new StubTool("other_tool", "other"));
        catalog.addTool(new ToolSearchTool());
        catalog.addTool(new TodoWriteTool());
        AgentContext context = context(
                config,
                catalog,
                "只能调用 required_tool，禁止使用 blocked_tool",
                "");
        context.setToolInvocationContract(ToolInvocationContract.resolve(
                context.getQuery(), catalog.getToolMap().keySet()));

        ContextPipeline.PreparedModelTurn turn = new ContextPipeline().prepareTurn(
                context,
                config,
                new ContextPipeline.PromptState("system", "next"),
                new Memory(),
                catalog,
                1);
        ToolCollection exposed = turn.exposedTools();

        Assert.assertEquals(2, exposed.toolCount());
        Assert.assertTrue(exposed.getToolMap().containsKey("required_tool"));
        Assert.assertTrue(exposed.getToolMap().containsKey(TodoWriteTool.NAME));
        Assert.assertFalse(exposed.getToolMap().containsKey("blocked_tool"));
        Assert.assertFalse(exposed.getToolMap().containsKey("other_tool"));
    }

    @Test
    public void exclusiveDeferredContractShouldExposeProxyAndTodoButNotNativeSchema() {
        ReactorConfig config = config("filtered", 1, 1);
        ToolCollection catalog = catalog(3);
        catalog.addTool(new TodoWriteTool());
        AgentContext context = context(
                config,
                catalog,
                "必须且只能调用 MCP 工具 list_events 一次",
                "");
        context.setToolInvocationContract(ToolInvocationContract.resolve(
                context.getQuery(), catalog.getMcpToolMap().keySet()));

        ToolCollection exposed = ToolExposurePolicy.selectForTurn(catalog, context, config);

        Assert.assertEquals(
                java.util.Set.of(ExecuteExtraTool.NAME, TodoWriteTool.NAME),
                exposed.getToolMap().keySet());
        Assert.assertTrue(exposed.getMcpToolMap().isEmpty());
        Assert.assertTrue(context.getToolInvocationContract().requiredToolNames()
                .contains("mcp__calendar__list_events"));
    }

    private ReactorConfig config(String mode, int inlineLimit, int selectedLimit) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "toolExposureMode", mode);
        ReflectionTestUtils.setField(config, "toolExposureMaxInlineMcpTools", inlineLimit);
        ReflectionTestUtils.setField(config, "toolExposureMaxSelectedMcpTools", selectedLimit);
        ReflectionTestUtils.setField(config, "toolExposureSearchDefaultLimit", 6);
        return config;
    }

    private ToolCollection catalog(int count) {
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(new StubTool("deep_search", "联网搜索"));
        ToolSearchTool searchTool = new ToolSearchTool();
        catalog.addTool(searchTool);
        catalog.addTool(new ExecuteExtraTool());
        catalog.addMcpTool(McpToolInfo.builder()
                .mcpId("calendar")
                .name("list_events")
                .exposedName("mcp__calendar__list_events")
                .desc("查询团队日历、会议和事件")
                .parameters("{\"query\":{\"type\":\"string\"}}")
                .build());
        for (int i = 1; i < count; i++) {
            catalog.addMcpTool(McpToolInfo.builder()
                    .mcpId("server" + i)
                    .name("unrelated_tool_" + i)
                    .exposedName("mcp__server" + i + "__unrelated_tool_" + i)
                    .desc("处理无关的扩展能力 " + i)
                    .parameters("{}")
                    .build());
        }
        return catalog;
    }

    private ToolCollection platformCatalog() {
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(new StubTool("deep_search", "联网搜索"));
        catalog.addTool(new PlatformContextTool());
        catalog.addTool(new ToolSearchTool());
        return catalog;
    }

    private AgentContext context(ReactorConfig config, ToolCollection catalog, String query, String task) {
        ReactorRuntimeDependencies dependencies = ReactorRuntimeTestSupport.runtimeDependencies(config);
        AgentContext context = AgentContext.builder()
                .requestId("req-tool-exposure")
                .sessionId("session-tool-exposure")
                .query(query)
                .task(task)
                .dateInfo("2026-07-16")
                .basePrompt("")
                .historyDialogue("")
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .toolCollection(catalog)
                .runtimeDependencies(dependencies)
                .build();
        catalog.setAgentContext(context);
        ToolSearchTool searchTool = (ToolSearchTool) catalog.getTool("tool_search");
        searchTool.setAgentContext(context);
        return context;
    }

    private record StubTool(String name, String description) implements BaseTool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Object execute(Object input) {
            return "ok";
        }
    }
}
