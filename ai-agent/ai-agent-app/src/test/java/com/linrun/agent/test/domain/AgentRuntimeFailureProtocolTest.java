package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.AgentRuntime;
import com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchGraphRunner;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;

import java.util.Map;

/** Canonical terminal protocol must survive unexpected harness initialization failures. */
public class AgentRuntimeFailureProtocolTest {

    @Test
    public void shouldPersistResolvedRoleSnapshotWithoutChangingEntryAgentMode() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(40L, "req-role-ledger"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("stop after ledger initialization"));

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies);
        runner.run(AgentRequest.builder()
                .requestId("req-role-ledger")
                .sessionId("session-role-ledger")
                .ownerId("1001")
                .query("review architecture")
                .aiAgentId("role-architecture-reviewer")
                .resolvedRoleName("架构审查助手")
                .executionMode("DEEP")
                .build(), printer);

        ArgumentCaptor<DialogueRunStartRecord> startCaptor = ArgumentCaptor.forClass(DialogueRunStartRecord.class);
        Mockito.verify(recorder).claimRun(startCaptor.capture());
        DialogueRunStartRecord startRecord = startCaptor.getValue();
        Assert.assertEquals(ExecutionLedgerConstants.ENTRY_AGENT_LOOP_DEEP, startRecord.getEntryAgent());
        Assert.assertEquals("role-architecture-reviewer", startRecord.getRoleAgentId());
        Assert.assertEquals("架构审查助手", startRecord.getRoleAgentName());
    }

    @Test
    public void shouldFinishLedgerAndEmitTypedFailureWhenToolAssemblyThrows() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(41L, "req-runner-failure-001"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("tool registry unavailable"));

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-runner-failure-001")
                .sessionId("session-runner-failure-001")
                .ownerId("1001")
                .query("执行一个需要工具的任务")
                .executionMode("STANDARD")
                .isStream(true)
                .build(), printer);

        Assert.assertEquals("", answer);
        InOrder protocol = Mockito.inOrder(printer);
        protocol.verify(printer).send(Mockito.argThat(event -> event instanceof AgentStreamEvent.AgentStart));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.StageOutput output
                        && "failure_details".equals(output.outputType())));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && AgentStopReason.EXECUTION_ERROR.name().equals(failure.code())));
        Mockito.verify(recorder).finishRun(Mockito.argThat((DialogueRunFinishRecord record) ->
                record != null
                        && Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED).equals(record.getStatus())
                        && AgentStopReason.EXECUTION_ERROR.name().equals(record.getErrorCode())));
    }

    @Test
    public void shouldPersistSuccessfulTerminalBeforeAcknowledgingItToClient() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(46L, "req-terminal-order"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(new ToolCollection());
        Mockito.when(loopFactory.create(Mockito.any())).thenReturn(loop);
        Mockito.when(loop.run(Mockito.anyString())).thenReturn("durable answer");
        Mockito.when(loop.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(loop.getStopReason()).thenReturn(AgentStopReason.COMPLETED);
        Mockito.when(runtimeDependencies.requireReactorConfig()).thenReturn(new ReactorConfig());

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies, loopFactory);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-terminal-order")
                .sessionId("session-terminal-order")
                .ownerId("1001")
                .query("finish durably")
                .executionMode("STANDARD")
                .build(), printer);

        Assert.assertEquals("durable answer", answer);
        InOrder order = Mockito.inOrder(recorder, printer);
        order.verify(recorder).claimRun(Mockito.any());
        order.verify(printer).send(Mockito.argThat(event -> event instanceof AgentStreamEvent.AgentStart));
        order.verify(recorder).finishRun(Mockito.argThat((DialogueRunFinishRecord record) ->
                record != null
                        && Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS).equals(record.getStatus())
                        && "durable answer".equals(record.getFinalSummaryText())));
        order.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Complete complete
                        && "durable answer".equals(complete.summary())));
    }

    @Test
    public void shouldNeverAcknowledgeSuccessWhenDurableTerminalCannotBeWritten() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(47L, "req-terminal-write-failed"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(new ToolCollection());
        Mockito.when(loopFactory.create(Mockito.any())).thenReturn(loop);
        Mockito.when(loop.run(Mockito.anyString())).thenReturn("uncommitted answer");
        Mockito.when(loop.getState()).thenReturn(AgentState.FINISHED);
        Mockito.when(loop.getStopReason()).thenReturn(AgentStopReason.COMPLETED);
        Mockito.doThrow(new IllegalStateException("ledger unavailable"))
                .when(recorder).finishRun(Mockito.any());

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies, loopFactory);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-terminal-write-failed")
                .sessionId("session-terminal-write-failed")
                .ownerId("1001")
                .query("do not acknowledge before commit")
                .executionMode("STANDARD")
                .build(), printer);

        Assert.assertEquals("", answer);
        Mockito.verify(printer, Mockito.never()).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Complete));
        InOrder protocol = Mockito.inOrder(printer);
        protocol.verify(printer).send(Mockito.argThat(event -> event instanceof AgentStreamEvent.AgentStart));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.StageOutput output
                        && output.payload() instanceof Map<?, ?> map
                        && "RUN_FINALIZATION_FAILED".equals(map.get("errorCode"))
                        && Boolean.FALSE.equals(map.get("durableTerminalPersisted"))
                        && Boolean.TRUE.equals(map.get("retryable"))));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && "RUN_FINALIZATION_FAILED".equals(failure.code())));
        Mockito.verify(recorder, Mockito.times(2)).finishRun(Mockito.any());
    }

    @Test
    public void shouldDelegateRunLocalLoopCreationToInjectedFactory() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(42L, "req-factory-delegation"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenReturn(new ToolCollection());
        Mockito.when(loopFactory.create(Mockito.any()))
                .thenThrow(new IllegalStateException("factory extension rejected run"));

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies, loopFactory);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-factory-delegation")
                .sessionId("session-factory-delegation")
                .query("verify factory delegation")
                .executionMode("STANDARD")
                .build(), printer);

        Assert.assertEquals("", answer);
        Mockito.verify(loopFactory).create(Mockito.argThat(context ->
                context != null && context.getToolCollection() != null));
        Mockito.verify(recorder).finishRun(Mockito.argThat((DialogueRunFinishRecord record) ->
                record != null
                        && Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED).equals(record.getStatus())));
    }

    @Test
    public void shouldFailFastWhenExplicitNetworkLookupHasNoAvailableCapability() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(43L, "req-network-unavailable"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenReturn(new ToolCollection());

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies, loopFactory);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-network-unavailable")
                .sessionId("session-network-unavailable")
                .query("请联网搜索并查证 Codex 最新价格")
                .online(false)
                .executionMode("STANDARD")
                .build(), printer);

        Assert.assertEquals("", answer);
        Mockito.verifyNoInteractions(loopFactory);
        InOrder protocol = Mockito.inOrder(printer);
        protocol.verify(printer).send(Mockito.argThat(event -> event instanceof AgentStreamEvent.AgentStart));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE.name().equals(failure.code())));
        Mockito.verify(recorder).finishRun(Mockito.argThat((DialogueRunFinishRecord record) ->
                record != null
                        && Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED).equals(record.getStatus())
                        && AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE.name().equals(record.getErrorCode())));
    }

    @Test
    public void shouldFailFastWhenExplicitNamedToolIsNotInActiveCatalog() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(44L, "req-explicit-tool-unavailable"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenReturn(new ToolCollection());

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies, loopFactory);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-explicit-tool-unavailable")
                .sessionId("session-explicit-tool-unavailable")
                .query("必须且只能调用 MCP 工具 utility_estimate_llm_quota，禁止使用任何替代工具")
                .online(true)
                .executionMode("STANDARD")
                .build(), printer);

        Assert.assertEquals("", answer);
        Mockito.verifyNoInteractions(loopFactory);
        InOrder protocol = Mockito.inOrder(printer);
        protocol.verify(printer).send(Mockito.argThat(event -> event instanceof AgentStreamEvent.AgentStart));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.StageOutput output
                        && output.payload() instanceof Map<?, ?> map
                        && "TOOL".equals(map.get("requiredCapability"))
                        && "utility_estimate_llm_quota".equals(map.get("requestedToolName"))
                        && "UNAVAILABLE".equals(map.get("capabilityResolution"))));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE.name().equals(failure.code())));
        Mockito.verify(recorder).finishRun(Mockito.argThat((DialogueRunFinishRecord record) ->
                record != null
                        && Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED).equals(record.getStatus())
                        && AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE.name().equals(record.getErrorCode())));
    }

    @Test
    public void shouldEmitQuotaFailureWhenDeepResearchCannotReserveQuota() throws Exception {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        DeepResearchGraphRunner deepResearchGraphRunner = Mockito.mock(DeepResearchGraphRunner.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(48L, "req-deep-quota"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenReturn(new ToolCollection());
        Mockito.when(deepResearchGraphRunner.run(Mockito.any(), Mockito.any()))
                .thenThrow(new QuotaInsufficientException("额度不足，无法支持最少256个输出Token"));

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies,
                loopFactory, deepResearchGraphRunner);
        String answer = runner.run(AgentRequest.builder()
                .requestId("req-deep-quota")
                .sessionId("session-deep-quota")
                .ownerId("1001")
                .query("深度调研一个行业")
                .executionMode("DEEP")
                .build(), printer);

        Assert.assertEquals("", answer);
        InOrder protocol = Mockito.inOrder(printer);
        protocol.verify(printer).send(Mockito.argThat(event -> event instanceof AgentStreamEvent.AgentStart));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.StageOutput output
                        && output.payload() instanceof Map<?, ?> map
                        && "QUOTA_INSUFFICIENT".equals(map.get("errorCode"))));
        protocol.verify(printer).send(Mockito.argThat(event ->
                event instanceof AgentStreamEvent.Error failure
                        && "QUOTA_INSUFFICIENT".equals(failure.code())
                        && failure.message().contains("额度不足")));
        Mockito.verify(recorder).finishRun(Mockito.argThat((DialogueRunFinishRecord record) ->
                record != null
                        && Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED).equals(record.getStatus())
                        && "QUOTA_INSUFFICIENT".equals(record.getErrorCode())
                        && record.getErrorMsg().contains("额度不足")));
    }

    @Test
    public void shouldAttachCanonicalInvocationContractBeforeCreatingLoop() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies runtimeDependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        stubLlm(runtimeDependencies);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        Printer printer = Mockito.mock(Printer.class);
        ToolCollection catalog = new ToolCollection();
        BaseTool required = Mockito.mock(BaseTool.class);
        BaseTool blocked = Mockito.mock(BaseTool.class);
        Mockito.when(required.getName()).thenReturn("required_tool");
        Mockito.when(blocked.getName()).thenReturn("blocked_tool");
        catalog.addTool(required);
        catalog.addTool(blocked);
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(newClaim(45L, "req-invocation-contract"));
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any())).thenReturn(catalog);
        Mockito.when(loopFactory.create(Mockito.any()))
                .thenThrow(new IllegalStateException("capture run context"));

        AgentRuntime runner = new AgentRuntime(toolFactory, recorder, runtimeDependencies, loopFactory);
        runner.run(AgentRequest.builder()
                .requestId("req-invocation-contract")
                .sessionId("session-invocation-contract")
                .query("只能调用 required_tool，禁止使用 blocked_tool")
                .online(true)
                .executionMode("STANDARD")
                .build(), printer);

        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        Mockito.verify(loopFactory).create(contextCaptor.capture());
        AgentContext context = contextCaptor.getValue();
        Assert.assertTrue(context.getToolInvocationContract().exclusive());
        Assert.assertEquals(
                java.util.Set.of("required_tool"),
                context.getToolInvocationContract().requiredToolNames());
        Assert.assertEquals(
                java.util.Set.of("blocked_tool"),
                context.getToolInvocationContract().forbiddenToolNames());
    }

    private void stubLlm(ReactorRuntimeDependencies dependencies) {
        Mockito.when(dependencies.resolveAgentLlmSettings(Mockito.any()))
                .thenReturn(LLMSettings.builder().model("test-model").build());
    }

    private DialogueRunClaim newClaim(long runId, String requestId) {
        return DialogueRunClaim.builder()
                .disposition(DialogueRunClaim.Disposition.NEW)
                .runId(runId)
                .runUid(requestId)
                .requestId(requestId)
                .runStatus(ExecutionLedgerConstants.STATUS_RUNNING)
                .build();
    }
}
