package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

public class AgentLoopResearchBranchExecutorIT {

    @Test
    public void shouldPassTheSubtaskContractToTheBoundedResearchLoop() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        ToolCollection tools = new ToolCollection();
        BaseTool deepSearch = Mockito.mock(BaseTool.class);
        Mockito.when(deepSearch.getName()).thenReturn("deep_search");
        tools.addTool(deepSearch);
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(tools);
        Mockito.when(loopFactory.create(Mockito.any())).thenReturn(loop);
        Mockito.when(loop.getRunBudget()).thenReturn(AgentRunBudget.defaults());
        Mockito.when(loop.run(Mockito.anyString())).thenReturn("没有工具来源");
        Mockito.when(loop.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(loop.getStopReason()).thenReturn(AgentStopReason.COMPLETED);
        AgentRequest request = AgentRequest.builder().requestId("branch-contract").sessionId("session")
                .ownerId("1001").query("比较成本和质量").executionMode("DEEP").build();
        AgentContext parent = AgentContext.builder().requestId("branch-contract").sessionId("session")
                .ownerId(1001L).query(request.getQuery()).build();

        new AgentLoopResearchBranchExecutor(toolFactory, loopFactory).execute(parent, request, ResearchPlan.create(request.getQuery()), 1);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(loop).run(prompt.capture());
        Assert.assertTrue(prompt.getValue().contains("子任务契约"));
        Assert.assertTrue(prompt.getValue().contains("claimId"));
        Assert.assertTrue(prompt.getValue().contains("ToolDispatcher 尝试 deep_search"));

        ArgumentCaptor<AgentContext> child = ArgumentCaptor.forClass(AgentContext.class);
        Mockito.verify(loopFactory).create(child.capture());
        Assert.assertEquals(AgentExecutionProfile.STANDARD, child.getValue().getExecutionProfile());
        ArgumentCaptor<com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall> toolCall =
                ArgumentCaptor.forClass(com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall.class);
        Mockito.verify(loop).executeTool(toolCall.capture());
        Assert.assertEquals("deep_search", toolCall.getValue().getFunction().getName());
        Assert.assertTrue(toolCall.getValue().getFunction().getArguments().contains("验证"));
        Assert.assertFalse(child.getValue().getToolInvocationContract().modelToolCallsAllowed());
        Assert.assertTrue(child.getValue().getToolInvocationContract().allows("deep_search"));
        Assert.assertEquals(java.util.Set.of("deep_search"),
                child.getValue().getToolInvocationContract().requiredToolNames());
    }

    @Test
    public void shouldUseDistinctStableEvidenceCallIdsForReviewerRestrictedPlan() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        ToolCollection tools = new ToolCollection();
        BaseTool deepSearch = Mockito.mock(BaseTool.class);
        Mockito.when(deepSearch.getName()).thenReturn("deep_search");
        tools.addTool(deepSearch);
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(tools);
        Mockito.when(loopFactory.create(Mockito.any())).thenReturn(loop);
        Mockito.when(loop.getRunBudget()).thenReturn(AgentRunBudget.defaults());
        Mockito.when(loop.run(Mockito.anyString())).thenReturn("完成");
        Mockito.when(loop.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(loop.getStopReason()).thenReturn(AgentStopReason.COMPLETED);
        AgentRequest request = AgentRequest.builder().requestId("branch-revision").sessionId("session")
                .ownerId("1001").query("比较成本和质量").executionMode("DEEP").build();
        AgentContext parent = AgentContext.builder().requestId("branch-revision").sessionId("session")
                .ownerId(1001L).query(request.getQuery()).build();
        AgentLoopResearchBranchExecutor executor = new AgentLoopResearchBranchExecutor(toolFactory, loopFactory);
        ResearchPlan initialPlan = ResearchPlan.create(request.getQuery());
        ResearchPlan revisedPlan = initialPlan.revision(List.of(initialPlan.subtasks().getFirst().id()));

        executor.execute(parent, request, initialPlan, 1);
        executor.execute(parent, request, revisedPlan, 1);

        ArgumentCaptor<com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall> calls =
                ArgumentCaptor.forClass(com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall.class);
        Mockito.verify(loop, Mockito.times(2)).executeTool(calls.capture());
        Assert.assertNotEquals(calls.getAllValues().get(0).getId(), calls.getAllValues().get(1).getId());
    }
}
