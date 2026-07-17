package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.PlatformContextToolOutput;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;

import java.util.List;
import java.util.Map;

public class PlatformContextToolTest {

    @Test
    public void schemaMustNotExposeIdentityAndDirectInjectionMustBeRejected() {
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        PlatformContextTool tool = tool(port, 42L);

        Map<?, ?> properties = (Map<?, ?>) tool.toParams().get("properties");
        Assert.assertFalse(properties.containsKey("userId"));
        Assert.assertFalse(properties.containsKey("ownerId"));
        Assert.assertEquals(Boolean.FALSE, tool.toParams().get("additionalProperties"));

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "operation", "orders",
                "userId", 999L));

        Assert.assertTrue(payload.getFailed());
        Assert.assertTrue(payload.getErrorMsg().contains("AgentContext"));
        Mockito.verifyNoInteractions(port);
    }

    @Test
    public void mustUseAgentContextOwnerAndReturnNavigationOnlyCta() {
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        Mockito.when(port.pricing(42L)).thenReturn(new PlatformContextPort.ContextResult<>(
                new PlatformContextPort.Pricing(List.of(), null),
                new PlatformContextPort.BffMeta(false, List.of())));
        PlatformContextTool tool = tool(port, 42L);

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of("operation", "pricing"));
        PlatformContextToolOutput output = (PlatformContextToolOutput) payload.getStructuredOutput();

        Assert.assertFalse(payload.getFailed());
        Assert.assertEquals("COMPLETE", output.getStatus());
        Assert.assertEquals("/pricing", output.getCta().path());
        Assert.assertEquals("查看套餐并购买", output.getCta().label());
        Assert.assertFalse(payload.getToolResult().contains("createOrder"));
        Assert.assertFalse(payload.getToolResult().contains("userId"));
        Assert.assertFalse(payload.getToolResult().contains("ownerId"));
        Assert.assertFalse(payload.getToolResult().contains("payUrl"));
        Mockito.verify(port).pricing(42L);
        Mockito.verifyNoMoreInteractions(port);
    }

    @Test
    public void degradedEmptyOrdersMustNotMasqueradeAsAuthoritativeEmpty() {
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        Mockito.when(port.orders(7L)).thenReturn(new PlatformContextPort.ContextResult<>(
                new PlatformContextPort.Orders(List.of()),
                new PlatformContextPort.BffMeta(true, List.of(
                        new PlatformContextPort.Degradation("pay", "ORDER_LIST_UNAVAILABLE", "timeout")))));
        PlatformContextTool tool = tool(port, 7L);

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of("operation", "orders"));
        PlatformContextToolOutput output = (PlatformContextToolOutput) payload.getStructuredOutput();

        Assert.assertTrue(payload.getFailed());
        Assert.assertEquals("DEGRADED", output.getStatus());
        Assert.assertFalse(output.getComplete());
        Assert.assertTrue(output.getDegraded());
        Assert.assertFalse(output.getAuthoritativeEmpty());
        Assert.assertTrue(output.getData() instanceof PlatformContextPort.Orders);
        Assert.assertTrue(payload.getLlmObservation().contains("不能解释为确认无数据"));
        Assert.assertEquals("/orders", output.getCta().path());
    }

    @Test
    public void groupBuyActivityIsOptionalAndForwardedWithoutIdentityInput() {
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        Mockito.when(port.groupBuy(9L, 101L)).thenReturn(new PlatformContextPort.ContextResult<>(
                new PlatformContextPort.GroupBuy(101L, null, List.of()),
                new PlatformContextPort.BffMeta(false, List.of())));
        PlatformContextTool tool = tool(port, 9L);

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "operation", "group_buy",
                "activityId", 101L));
        PlatformContextToolOutput output = (PlatformContextToolOutput) payload.getStructuredOutput();

        Assert.assertFalse(payload.getFailed());
        Assert.assertEquals("/group-buy/101", output.getCta().path());
        Mockito.verify(port).groupBuy(9L, 101L);
    }

    @Test
    public void missingAuthenticatedOwnerMustFailBeforeCallingBff() {
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        PlatformContextTool tool = tool(port, null);

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of("operation", "account_summary"));

        Assert.assertTrue(payload.getFailed());
        Assert.assertTrue(payload.getErrorMsg().contains("ownerId"));
        Mockito.verifyNoInteractions(port);
    }

    @Test
    public void recordDataMustSurviveJsonTransportForFrontendCards() {
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        Mockito.when(port.accountSummary(42L)).thenReturn(new PlatformContextPort.ContextResult<>(
                new PlatformContextPort.AccountSummary(
                        5_000_000L,
                        120_000_000L,
                        0L,
                        125_000_000L,
                        List.of(),
                        List.of()),
                new PlatformContextPort.BffMeta(false, List.of())));

        ToolResultPayload payload = (ToolResultPayload) tool(port, 42L)
                .execute(Map.of("operation", "account_summary"));

        Assert.assertFalse(payload.getFailed());
        Assert.assertTrue(payload.getToolResult().contains("\"availableQuota\":125000000"));
        Assert.assertTrue(payload.getToolResult().contains("\"paidQuotaBalance\":120000000"));
        Assert.assertFalse(payload.getToolResult().contains("ownerId"));
        Assert.assertFalse(payload.getToolResult().contains("userId"));
    }

    private PlatformContextTool tool(PlatformContextPort port, Long ownerId) {
        ReactorRuntimeDependencies dependencies = ReactorRuntimeTestSupport
                .runtimeDependencies(new ReactorConfig())
                .toBuilder()
                .platformContextPort(port)
                .build();
        PlatformContextTool tool = new PlatformContextTool();
        tool.setAgentContext(AgentContext.builder()
                .requestId("platform-context-test")
                .ownerId(ownerId)
                .runtimeDependencies(dependencies)
                .build());
        return tool;
    }
}
