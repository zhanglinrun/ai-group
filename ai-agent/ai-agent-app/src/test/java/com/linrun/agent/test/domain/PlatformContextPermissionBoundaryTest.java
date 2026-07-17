package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;

import java.util.Map;

public class PlatformContextPermissionBoundaryTest {

    @Test
    public void shouldRequireAuthenticatedContextOwner() {
        DefaultPermissionPolicy policy = new DefaultPermissionPolicy();
        ToolCollection active = activeTools();

        PermissionPolicy.PermissionDecision decision = policy.evaluate(
                PlatformContextTool.NAME,
                Map.of("operation", "orders"),
                active,
                AgentContext.builder().build());

        Assert.assertFalse(decision.allowed());
        Assert.assertTrue(decision.reason().contains("authenticated AgentContext owner"));
    }

    @Test
    public void shouldRejectModelControlledIdentityEvenWithAuthenticatedOwner() {
        DefaultPermissionPolicy policy = new DefaultPermissionPolicy();
        ToolCollection active = activeTools();
        AgentContext context = AgentContext.builder().ownerId(42L).build();

        PermissionPolicy.PermissionDecision injected = policy.evaluate(
                PlatformContextTool.NAME,
                Map.of("operation", "orders", "userId", 999L),
                active,
                context);
        PermissionPolicy.PermissionDecision clean = policy.evaluate(
                PlatformContextTool.NAME,
                Map.of("operation", "orders"),
                active,
                context);

        Assert.assertFalse(injected.allowed());
        Assert.assertTrue(injected.reason().contains("must come from AgentContext"));
        Assert.assertTrue(clean.allowed());
    }

    private ToolCollection activeTools() {
        ToolCollection tools = new ToolCollection();
        tools.addTool(new PlatformContextTool());
        return tools;
    }
}
