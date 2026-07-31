package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunContext;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.DefaultAgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.HarnessErrorCode;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

/** Direct P20 proof that one Harness owns loop, ledger and quota boundaries. */
public class AgentHarnessFacadeTest {

    @Test
    public void shouldBindCompleteRunContextAndRouteBoundedToolLoop() {
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        AgentContext context = context(null, null);
        ToolCall preflight = toolCall("preflight-search", "deep_search");
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.run("research prompt")).thenReturn("research answer");

        DefaultAgentHarnessFacade facade = new DefaultAgentHarnessFacade(loopFactory);
        AgentRunContext run = facade.bind(context);
        AgentHarnessFacade.ToolLoopResult result = facade.runToolLoop(context,
                new AgentHarnessFacade.ToolLoopRequest("research prompt",
                        AgentRunBudget.defaults().withMaxTurns(4), false, List.of(preflight)));

        Assert.assertEquals("tenant-a", run.tenantId());
        Assert.assertEquals(1001L, run.userId());
        Assert.assertEquals(77L, run.runId());
        Assert.assertEquals(9L, run.fencingToken());
        Assert.assertNotNull(run.deadline());
        Assert.assertEquals("research answer", result.answer());
        Assert.assertSame(loop, result.agentLoop());
        Mockito.verify(loop).setPropagateFailureToContext(false);
        Mockito.verify(loop).setRunBudget(Mockito.any(AgentRunBudget.class));
        Mockito.verify(loop).executeTool(preflight);
        Mockito.verify(loop).run("research prompt");
    }

    @Test
    public void shouldRecordModelAndToolAttemptsBeforeReturningToCaller() {
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        AgentContext context = context(recorder, null);
        Mockito.when(recorder.createLlmInvocation(Mockito.any())).thenReturn(301L);
        Mockito.when(recorder.createToolInvocations(Mockito.any()))
                .thenReturn(Map.of("tool-call-1", 401L));

        DefaultAgentHarnessFacade facade = new DefaultAgentHarnessFacade(loopFactory);
        Long modelId = facade.recordModelInvocation(context,
                new AgentHarnessFacade.ModelInvocationRecord("structured_step", "test-model", "prompt-hash",
                        false, ExecutionLedgerConstants.STATUS_SUCCESS, "ok", null));
        Long toolId = facade.recordToolAttempt(context,
                new AgentHarnessFacade.ToolAttemptRecord("tool-call-1", "deep_search", "{}",
                        ExecutionLedgerConstants.STATUS_SUCCESS, "result", null));

        Assert.assertEquals(Long.valueOf(301L), modelId);
        Assert.assertEquals(Long.valueOf(401L), toolId);
        Mockito.verify(recorder).finishLlmInvocation(Mockito.argThat(record ->
                record != null && Long.valueOf(301L).equals(record.getLlmInvocationId())
                        && "req-harness".equals(record.getRequestId())));
        Mockito.verify(recorder).finishToolInvocation(Mockito.argThat(record ->
                record != null && Long.valueOf(401L).equals(record.getToolInvocationId())
                        && "tool-call-1".equals(record.getToolCallId())));
    }

    @Test
    public void shouldDelegateQuotaReserveSettleAndReleaseThroughExistingPort() {
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        QuotaBillingPort quota = Mockito.mock(QuotaBillingPort.class);
        AgentContext context = context(null, quota);
        QuotaBillingPort.Reservation reservation = new QuotaBillingPort.Reservation("freeze-harness", 800L);
        QuotaBillingPort.SettlementResult settled = new QuotaBillingPort.SettlementResult(
                "freeze-harness", QuotaBillingPort.ReservationState.CONFIRMED, 500L);
        QuotaBillingPort.SettlementResult released = new QuotaBillingPort.SettlementResult(
                "freeze-harness", QuotaBillingPort.ReservationState.RELEASED, 0L);
        Mockito.when(quota.reserve(1001L, 800L, 256L, "structured_step", "req-harness:harness:structured_step"))
                .thenReturn(reservation);
        Mockito.when(quota.settleWithStatus("freeze-harness", 500L)).thenReturn(settled);
        Mockito.when(quota.releaseWithStatus("freeze-harness")).thenReturn(released);

        DefaultAgentHarnessFacade facade = new DefaultAgentHarnessFacade(loopFactory);

        Assert.assertSame(reservation, facade.reserveQuota(context, "structured_step", 800L, 256L));
        Assert.assertSame(settled, facade.settleQuota(context, "freeze-harness", 500L));
        Assert.assertSame(released, facade.releaseQuota(context, "freeze-harness"));
    }

    @Test
    public void shouldKeepTypedFailuresExplainable() {
        Assert.assertEquals(HarnessErrorCode.QUOTA_INSUFFICIENT,
                HarnessErrorCode.from(new com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException("额度不足"),
                        AgentStopReason.MODEL_ERROR));
        Assert.assertEquals(HarnessErrorCode.SCHEMA_INVALID,
                HarnessErrorCode.from(new IllegalArgumentException("schema validation failed"), AgentStopReason.NONE));
        Assert.assertEquals(HarnessErrorCode.TOOL_DENIED,
                HarnessErrorCode.from(new IllegalStateException("tool denied by policy"), AgentStopReason.NONE));
    }

    @Test
    public void shouldRejectTextThatOnlyLooksLikeJsonForStructuredStep() {
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        AgentContext context = context(null, null);
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.step()).thenReturn("{plan: not-json}");

        DefaultAgentHarnessFacade facade = new DefaultAgentHarnessFacade(loopFactory);
        try {
            facade.runStructuredStep(context, new AgentHarnessFacade.StructuredStepRequest(
                    "plan", AgentRunBudget.defaults(),
                    AgentHarnessFacade.StructuredOutputSchema.object("plan")));
            Assert.fail("invalid JSON must not satisfy a structured step contract");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("schema validation failed"));
            Assert.assertEquals(HarnessErrorCode.SCHEMA_INVALID,
                    HarnessErrorCode.from(expected, AgentStopReason.NONE));
        }
    }

    @Test
    public void shouldReturnCanonicalDispatcherOutcomeWithoutNormalizingFailures() {
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        AgentContext context = context(null, null);
        ToolCall toolCall = toolCall("tool-call-failure", "deep_search");
        ToolExecutionOutcome failure = ToolExecutionOutcome.failure(
                "tool denied", "tool denied", null, "approval required");
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.executeToolOutcome(toolCall)).thenReturn(failure);

        DefaultAgentHarnessFacade facade = new DefaultAgentHarnessFacade(loopFactory);

        Assert.assertSame(failure, facade.executeTool(context, toolCall));
        Assert.assertFalse(facade.executeTool(context, toolCall).isSuccess());
    }

    private AgentContext context(AgentExecutionRecorder recorder, QuotaBillingPort quota) {
        AgentRunState runState = AgentRunState.builder().build();
        runState.setRunId(77L);
        return AgentContext.builder()
                .tenantId("tenant-a")
                .ownerId(1001L)
                .sessionId("session-harness")
                .requestId("req-harness")
                .fencingToken(9L)
                .agentRunState(runState)
                .executionRecorder(recorder)
                .runtimeDependencies(ReactorRuntimeDependencies.builder().quotaBillingPort(quota).build())
                .build();
    }

    private ToolCall toolCall(String id, String name) {
        return ToolCall.builder()
                .id(id)
                .function(ToolCall.Function.builder().name(name).arguments("{}").build())
                .build();
    }
}
