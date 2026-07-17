package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;

import java.util.List;
import java.util.Map;

public class ToolInvocationContractTest {

    @Test
    public void shouldResolveChineseExclusiveAndForbiddenNamesToCanonicalCatalogNames() {
        ToolInvocationContract contract = ToolInvocationContract.resolve(
                "必须调用 required_tool，禁止使用 blocked_tool、missing_tool 或任何替代工具",
                List.of(
                        "mcp__utility__required_tool",
                        "mcp__utility__blocked_tool",
                        "other_tool"));

        Assert.assertTrue(contract.exclusive());
        Assert.assertEquals(
                java.util.Set.of("mcp__utility__required_tool"),
                contract.requiredToolNames());
        Assert.assertEquals(
                java.util.Set.of("mcp__utility__required_tool"),
                contract.allowedToolNames());
        Assert.assertEquals(
                java.util.Set.of("mcp__utility__blocked_tool"),
                contract.forbiddenToolNames());
        Assert.assertFalse(contract.forbiddenToolNames().contains("missing_tool"));
        Assert.assertTrue(contract.allows(TodoWriteTool.NAME));
        Assert.assertFalse(contract.allows("other_tool"));
    }

    @Test
    public void shouldResolveEnglishOnlyCallContract() {
        ToolInvocationContract contract = ToolInvocationContract.resolve(
                "Only call required_tool; do not use blocked_tool.",
                List.of("required_tool", "blocked_tool", "other_tool"));

        Assert.assertTrue(contract.exclusive());
        Assert.assertEquals(java.util.Set.of("required_tool"), contract.requiredToolNames());
        Assert.assertEquals(java.util.Set.of("blocked_tool"), contract.forbiddenToolNames());
        Assert.assertTrue(contract.allows("required_tool"));
        Assert.assertFalse(contract.allows("blocked_tool"));
        Assert.assertFalse(contract.allows("other_tool"));

        ToolInvocationContract noAlternative = ToolInvocationContract.resolve(
                "Must call required_tool without any alternative tool.",
                List.of("required_tool", "blocked_tool", "other_tool"));
        Assert.assertTrue(noAlternative.exclusive());
        Assert.assertTrue(noAlternative.allows("required_tool"));
        Assert.assertFalse(noAlternative.allows("other_tool"));
        Assert.assertTrue(noAlternative.allows(TodoWriteTool.NAME));
    }

    @Test
    public void permissionShouldRejectInjectedForbiddenToolEvenWhenActiveViewContainsIt() {
        ToolCollection forgedActiveView = new ToolCollection();
        forgedActiveView.addTool(new StubTool("required_tool"));
        forgedActiveView.addTool(new StubTool("blocked_tool"));
        forgedActiveView.addTool(new TodoWriteTool());
        AgentContext context = AgentContext.builder()
                .query("只能调用 required_tool，禁止使用 blocked_tool")
                .toolInvocationContract(ToolInvocationContract.resolve(
                        "只能调用 required_tool，禁止使用 blocked_tool",
                        List.of("required_tool", "blocked_tool", TodoWriteTool.NAME)))
                .build();
        DefaultPermissionPolicy policy = new DefaultPermissionPolicy();

        PermissionPolicy.PermissionDecision blocked = policy.evaluate(
                "blocked_tool", Map.of(), forgedActiveView, context);
        PermissionPolicy.PermissionDecision required = policy.evaluate(
                "required_tool", Map.of(), forgedActiveView, context);
        PermissionPolicy.PermissionDecision todo = policy.evaluate(
                TodoWriteTool.NAME, Map.of(), forgedActiveView, context);

        Assert.assertFalse(blocked.allowed());
        Assert.assertTrue(blocked.reason().contains("invocation contract"));
        Assert.assertTrue(required.allowed());
        Assert.assertTrue(todo.allowed());
    }

    private record StubTool(String name) implements BaseTool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "stub";
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
