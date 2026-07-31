package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AgentLoopResearchBranchExecutorTest {

    @Test
    public void shouldUseStableBoundedIdForLongExtractedClaim() {
        String claim = IntStream.range(0, 500).mapToObj(index -> "fact" + index)
                .collect(Collectors.joining(" "));

        String first = AgentLoopResearchBranchExecutor.canonicalClaimId(claim);
        String second = AgentLoopResearchBranchExecutor.canonicalClaimId(claim);

        Assert.assertEquals(first, second);
        Assert.assertTrue(first.startsWith("claim-"));
        Assert.assertTrue(first.length() <= 128);
    }

    @Test
    public void shouldExposeOnlyExplicitlyNamedMcpToolForResearchBranch() {
        ToolCollection catalog = catalogWithProjectKnowledgeTool();

        String selected = AgentLoopResearchBranchExecutor.explicitlyRequestedMcpTool(
                catalog, "请调用 project_search_knowledge 检索项目架构。");

        Assert.assertEquals("mcp__dev_mcp_project_knowledge_001__project_search_knowledge", selected);
    }

    @Test
    public void shouldNotExposeMcpToolForGenericOrUnknownResearchRequest() {
        ToolCollection catalog = catalogWithProjectKnowledgeTool();

        Assert.assertNull(AgentLoopResearchBranchExecutor.explicitlyRequestedMcpTool(
                catalog, "请调研本项目的 Agent 架构并给出可引用来源。"));
        Assert.assertNull(AgentLoopResearchBranchExecutor.explicitlyRequestedMcpTool(
                catalog, "请调用 unavailable_project_lookup 检索项目架构。"));
    }

    @Test
    public void shouldResolveNamedProjectMcpWhenOtherMcpToolsAreAlsoActive() {
        ToolCollection catalog = catalogWithProjectKnowledgeTool();
        catalog.addMcpTool(McpToolInfo.builder()
                .mcpId("dev_mcp_agent_utility_001")
                .name("utility_explain_quota_formula")
                .exposedName("mcp__dev_mcp_agent_utility_001__utility_explain_quota_formula")
                .parameters("{\"type\":\"object\",\"properties\":{}}")
                .build());

        String selected = AgentLoopResearchBranchExecutor.explicitlyRequestedMcpTool(catalog,
                "深度调研 OpenJDK JEP 444 Virtual Threads 的 Java 21 状态，且请调用 "
                        + "project_search_knowledge 作为辅助检索。必须给出至少一个 OpenJDK 或 JEP 的真实 URL。 ");

        Assert.assertEquals("mcp__dev_mcp_project_knowledge_001__project_search_knowledge", selected);
    }

    @Test
    public void shouldSelectMcpWhenUserAlsoNamesIndependentLocalReportTool() {
        ToolCollection catalog = catalogWithProjectKnowledgeTool();
        catalog.addTool(namedLocalTool("report_tool"));

        String selected = AgentLoopResearchBranchExecutor.explicitlyRequestedMcpTool(catalog,
                "请调用 project_search_knowledge 作为辅助检索，并调用 report_tool 生成短报告。 ");

        Assert.assertEquals("mcp__dev_mcp_project_knowledge_001__project_search_knowledge", selected);
    }

    @Test
    public void shouldPreflightOnlyExplicitMcpAlongsideRequiredWebEvidence() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentHarnessFacade harness = Mockito.mock(AgentHarnessFacade.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        ToolCollection catalog = catalogWithSearchAndProjectKnowledgeTool();
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(catalog);
        Mockito.when(harness.runToolLoop(Mockito.any(), Mockito.any()))
                .thenReturn(new AgentHarnessFacade.ToolLoopResult("branch result", loop));
        Mockito.when(loop.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(loop.getStopReason()).thenReturn(AgentStopReason.COMPLETED);

        AgentContext parent = AgentContext.builder()
                .requestId("req-explicit-mcp")
                .sessionId("session-explicit-mcp")
                .ownerId(1001L)
                .query("请调用 project_search_knowledge 检索本项目的 Agent 架构，并给出公开来源。")
                .build();
        AgentRequest request = AgentRequest.builder()
                .requestId(parent.getRequestId())
                .sessionId(parent.getSessionId())
                .ownerId("1001")
                .query("Research graph request metadata without an explicit tool directive.")
                .executionMode("DEEP")
                .online(true)
                .build();

        new AgentLoopResearchBranchExecutor(toolFactory, loopFactory, harness)
                .execute(parent, request, ResearchPlan.create(parent.getQuery()), 1);

        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        ArgumentCaptor<AgentHarnessFacade.ToolLoopRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentHarnessFacade.ToolLoopRequest.class);
        Mockito.verify(harness).runToolLoop(contextCaptor.capture(), requestCaptor.capture());
        Assert.assertTrue(contextCaptor.getValue().getToolCollection().getMcpToolMap()
                .containsKey("mcp__dev_mcp_project_knowledge_001__project_search_knowledge"));
        Assert.assertEquals(2, requestCaptor.getValue().preflightToolCalls().size());
        Assert.assertTrue(requestCaptor.getValue().preflightToolCalls().stream()
                .anyMatch(call -> "search_web".equals(call.getFunction().getName())));
        Assert.assertTrue(requestCaptor.getValue().preflightToolCalls().stream()
                .anyMatch(call -> "mcp__dev_mcp_project_knowledge_001__project_search_knowledge"
                        .equals(call.getFunction().getName())));
    }

    @Test
    public void shouldReturnDeterministicGapWhenOfflineResearchHasNoNetworkPreflight() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentHarnessFacade harness = Mockito.mock(AgentHarnessFacade.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(namedLocalTool("extract_evidence"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(catalog);

        AgentContext parent = AgentContext.builder()
                .requestId("req-offline-composition")
                .sessionId("session-offline-composition")
                .ownerId(1001L)
                .query("生成一个离线表格，说明最小验收项。")
                .build();
        AgentRequest request = AgentRequest.builder()
                .requestId(parent.getRequestId())
                .sessionId(parent.getSessionId())
                .ownerId("1001")
                .query(parent.getQuery())
                .executionMode("DEEP")
                .online(false)
                .build();

        ResearchBranchResult result = new AgentLoopResearchBranchExecutor(toolFactory, loopFactory, harness)
                .execute(parent, request, ResearchPlan.create(parent.getQuery()), 1);

        Assert.assertTrue(result.evidence().isEmpty());
        Assert.assertTrue(result.gaps().stream().anyMatch(gap -> gap.contains("search_web/fetch_page")));
        Mockito.verifyNoInteractions(harness, loopFactory);
    }

    private ToolCollection catalogWithProjectKnowledgeTool() {
        ToolCollection catalog = new ToolCollection();
        catalog.addMcpTool(McpToolInfo.builder()
                .mcpId("dev_mcp_project_knowledge_001")
                .name("project_search_knowledge")
                .exposedName("mcp__dev_mcp_project_knowledge_001__project_search_knowledge")
                .parameters("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}")
                .build());
        return catalog;
    }

    private ToolCollection catalogWithSearchAndProjectKnowledgeTool() {
        ToolCollection catalog = catalogWithProjectKnowledgeTool();
        catalog.addTool(namedLocalTool("search_web"));
        return catalog;
    }

    private BaseTool namedLocalTool(String name) {
        return new BaseTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "test search";
            }

            @Override
            public java.util.Map<String, Object> toParams() {
                return java.util.Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                return null;
            }
        };
    }

    @Test
    public void shouldKeepParentCancellationTokenInResearchBranch() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenReturn(new ToolCollection());
        Mockito.when(loopFactory.create(Mockito.any())).thenReturn(loop);
        Mockito.when(loop.getRunBudget()).thenReturn(AgentRunBudget.defaults());
        Mockito.when(loop.run(Mockito.anyString())).thenReturn("branch result");
        Mockito.when(loop.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(loop.getStopReason()).thenReturn(AgentStopReason.COMPLETED);

        AgentContext parent = AgentContext.builder()
                .requestId("req-cancel-propagation")
                .sessionId("session-cancel-propagation")
                .ownerId(1001L)
                .query("research")
                .build();
        parent.activateRunDeadline(60_000L);
        AgentRequest request = AgentRequest.builder()
                .requestId(parent.getRequestId())
                .sessionId(parent.getSessionId())
                .ownerId("1001")
                .query(parent.getQuery())
                .executionMode("DEEP")
                .build();

        new AgentLoopResearchBranchExecutor(toolFactory, loopFactory)
                .execute(parent, request, ResearchPlan.create(request.getQuery()), 1);

        Mockito.verify(loop).setPropagateFailureToContext(false);
        ArgumentCaptor<AgentContext> childCaptor = ArgumentCaptor.forClass(AgentContext.class);
        Mockito.verify(loopFactory).create(childCaptor.capture());
        AgentContext child = childCaptor.getValue();
        Assert.assertSame(parent.getCancellationToken(), child.getCancellationToken());

        parent.cancel(AgentStopReason.DOWNSTREAM_ABORTED);
        Assert.assertEquals(AgentStopReason.DOWNSTREAM_ABORTED, child.cancellationReason());
    }
}
