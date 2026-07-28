package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class AgentLoopResearchBranchExecutorTest {

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

        ArgumentCaptor<AgentContext> childCaptor = ArgumentCaptor.forClass(AgentContext.class);
        Mockito.verify(loopFactory).create(childCaptor.capture());
        AgentContext child = childCaptor.getValue();
        Assert.assertSame(parent.getCancellationToken(), child.getCancellationToken());

        parent.cancel(AgentStopReason.DOWNSTREAM_ABORTED);
        Assert.assertEquals(AgentStopReason.DOWNSTREAM_ABORTED, child.cancellationReason());
    }
}
