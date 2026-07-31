package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.BaseAgent;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.StopGate;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentHarnessComponentsTest {

    @Test
    public void shouldShareStructuredCancellationWithForkedContext() {
        AgentContext parent = AgentContext.builder().build();
        parent.activateRunDeadline(60_000L);
        AgentContext child = parent.forkForParallelTask("child");

        parent.cancel(AgentStopReason.DOWNSTREAM_ABORTED);

        Assert.assertEquals(AgentStopReason.DOWNSTREAM_ABORTED, child.cancellationReason());
        Assert.assertSame(parent.getCancellationToken(), child.getCancellationToken());
    }

    @Test
    public void shouldPreserveParentDeadlineWhenStartingResearchBranch() {
        AgentContext parent = AgentContext.builder().build();
        parent.activateRunDeadline(1_000L);
        AgentContext child = parent.forkForParallelTask("researcher_1");

        new StopGate().beginRun(child, new AgentRunBudget(4, 4, 1, 120_000L, 100, 100));

        Assert.assertTrue("a branch must not replace the shared parent deadline",
                child.remainingRunDuration().toMillis() < 1_500L);
    }

    @Test
    public void shouldCancelInFlightWaitWhenParentTokenIsCancelled() throws Exception {
        AgentContext context = AgentContext.builder().build();
        context.activateRunDeadline(60_000L);
        CompletableFuture<String> future = new CompletableFuture<>();
        context.cancel(AgentStopReason.EXECUTION_ERROR);

        try {
            AgentFutureWaiter.await(future, context, Duration.ofSeconds(10));
            Assert.fail("expected structured cancellation");
        } catch (AgentFutureWaiter.RunCancelledException cancelledException) {
            Assert.assertEquals(AgentStopReason.EXECUTION_ERROR, cancelledException.getStopReason());
            Assert.assertTrue(future.isCancelled());
        }
    }

    @Test
    public void shouldDenyOfflineMcpExecutionEvenWhenCatalogContainsTool() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = McpToolInfo.builder()
                .mcpId("remote")
                .name("search")
                .exposedName("mcp__remote__search")
                .desc("remote search")
                .parameters("{}")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_SSE)
                .origin(McpToolOrigin.CONFIGURED)
                .build();
        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        tools.addMcpTool(toolInfo);
        AgentContext context = AgentContext.builder()
                .requestId("offline-permission")
                .online(false)
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        BaseAgent agent = toolAgent(tools, context);

        String result = agent.executeTool(toolCall("offline-call", "mcp__remote__search"));

        Assert.assertTrue(result.contains("disabled"));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldAllowConfiguredStdioMcpExecutionWhenOffline() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = McpToolInfo.builder()
                .mcpId("local-configured")
                .name("estimate")
                .exposedName("mcp__local_configured__estimate")
                .desc("local deterministic calculator")
                .parameters("{}")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .origin(McpToolOrigin.CONFIGURED)
                .build();
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.text("calculated"));
        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        tools.addMcpTool(toolInfo);
        AgentContext context = AgentContext.builder()
                .requestId("offline-stdio-permission")
                .online(false)
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        BaseAgent agent = toolAgent(tools, context);

        String result = agent.executeTool(toolCall("offline-stdio-call", "mcp__local_configured__estimate"));

        Assert.assertTrue(result.contains("calculated"));
        Mockito.verify(executor).executeTool(Mockito.eq(toolInfo), Mockito.any());
    }

    @Test
    public void shouldDenyUserMcpWhenOfflineEvenIfItIsInjectedIntoCatalog() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = McpToolInfo.builder()
                .mcpId("user:1001:test")
                .name("user_tool")
                .exposedName("mcp__user_1001_test__user_tool")
                .desc("user supplied tool")
                .parameters("{}")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .origin(McpToolOrigin.USER_EXTENSION)
                .build();
        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        tools.addMcpTool(toolInfo);
        AgentContext context = AgentContext.builder()
                .requestId("offline-user-mcp-permission")
                .online(false)
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        BaseAgent agent = toolAgent(tools, context);

        String result = agent.executeTool(toolCall("offline-user-call", "mcp__user_1001_test__user_tool"));

        Assert.assertTrue(result.contains("disabled"));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldFailClosedForMissingOfflineMcpMetadata() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        tools.getMcpToolMap().put("mcp__unknown__tool", null);
        AgentContext context = AgentContext.builder()
                .requestId("offline-missing-metadata")
                .online(false)
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        BaseAgent agent = toolAgent(tools, context);

        String result = agent.executeTool(toolCall("offline-null-call", "mcp__unknown__tool"));

        Assert.assertTrue(result.contains("disabled"));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldAllowPreToolHookToBlockExecution() {
        AtomicInteger executions = new AtomicInteger();
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool("blocked_tool", false, executions, null));
        AgentContext context = AgentContext.builder()
                .requestId("hook-deny")
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        BaseAgent agent = toolAgent(tools, context);
        agent.getHookBus().register(event -> event.point() == HookBus.HookPoint.PRE_TOOL
                ? HookBus.HookDecision.deny("blocked by policy hook")
                : HookBus.HookDecision.allow());

        String result = agent.executeTool(toolCall("hook-call", "blocked_tool"));

        Assert.assertTrue(result.contains("blocked by policy hook"));
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldSerializeUnsafeToolsAndParallelizeExplicitlySafeTools() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Assert.assertEquals(1, maxObservedConcurrency(executor, false));
            Assert.assertTrue(maxObservedConcurrency(executor, true) >= 2);
        } finally {
            executor.shutdownNow();
        }
    }

    private int maxObservedConcurrency(ExecutorService executor, boolean concurrencySafe) throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool("concurrency_tool", concurrencySafe, active, () -> {
            int current = active.get();
            maxActive.accumulateAndGet(current, Math::max);
            bothStarted.countDown();
            try {
                bothStarted.await(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }));
        AgentContext context = AgentContext.builder()
                .requestId(concurrencySafe ? "safe-tools" : "unsafe-tools")
                .agentRunState(AgentRunState.builder().build())
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .toolExecutor(executor)
                        .build())
                .build();
        tools.setAgentContext(context);
        BaseAgent agent = toolAgent(tools, context);

        agent.executeTools(List.of(
                toolCall("call-1", "concurrency_tool"),
                toolCall("call-2", "concurrency_tool")));
        return maxActive.get();
    }

    private BaseTool tool(String name,
                          boolean concurrencySafe,
                          AtomicInteger counter,
                          Runnable duringExecution) {
        return new BaseTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "test";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                counter.incrementAndGet();
                try {
                    if (duringExecution != null) {
                        duringExecution.run();
                    }
                    return "ok";
                } finally {
                    if (duringExecution != null) {
                        counter.decrementAndGet();
                    }
                }
            }

            @Override
            public boolean isConcurrencySafe(Object input) {
                return concurrencySafe;
            }
        };
    }

    private BaseAgent toolAgent(ToolCollection tools, AgentContext context) {
        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                return "unused";
            }
        };
        agent.setName("harness-components-test");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        return agent;
    }

    private ToolCall toolCall(String id, String name) {
        return ToolCall.builder()
                .id(id)
                .function(ToolCall.Function.builder()
                        .name(name)
                        .arguments("{}")
                        .build())
                .build();
    }
}
