package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.BaseAgent;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.completion.CompletionRequest;
import com.linrun.agent.domain.agent.runtime.completion.DefaultEvidenceValidator;
import com.linrun.agent.domain.agent.runtime.dto.Memory;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.loop.ContextPipeline;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.ExecuteExtraTool;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolDispatcher;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ToolDispatcherBoundaryTest {

    @Test
    public void shouldRejectBlankIdBatchBeforeAnyToolExecutes() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = testAgent(executions, "无需使用任何工具，直接回答");

        try {
            agent.executeTools(List.of(
                    toolCall(" ", "guarded_tool", "{}"),
                    toolCall("valid-id", "guarded_tool", "{}")));
            Assert.fail("blank toolCallId must reject the whole batch");
        } catch (ToolDispatcher.ToolDispatchRejectedException rejected) {
            Assert.assertEquals(
                    ToolDispatcher.ToolDispatchRejectionReason.MISSING_TOOL_CALL_ID,
                    rejected.getReason());
            Assert.assertEquals(0, rejected.getBatchIndex());
        }
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldRejectDuplicateIdBatchBeforeAnyToolExecutes() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = testAgent(executions, "调用测试工具");

        try {
            agent.executeTools(List.of(
                    toolCall("duplicate-id", "guarded_tool", "{\"value\":1}"),
                    toolCall("duplicate-id", "guarded_tool", "{\"value\":2}")));
            Assert.fail("duplicate toolCallId must reject the whole batch");
        } catch (ToolDispatcher.ToolDispatchRejectedException rejected) {
            Assert.assertEquals(
                    ToolDispatcher.ToolDispatchRejectionReason.DUPLICATE_TOOL_CALL_ID,
                    rejected.getReason());
            Assert.assertEquals("duplicate-id", rejected.getToolCallId());
            Assert.assertEquals(1, rejected.getBatchIndex());
        }
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldDenyProviderToolCallsWhenUserProhibitsAllTools() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = testAgent(executions, "不要调用任何工具，直接回答");
        ContextPipeline pipeline = new ContextPipeline();
        ContextPipeline.PreparedModelTurn turn = pipeline.prepareTurn(
                agent.getContext(),
                new ReactorConfig(),
                new ContextPipeline.PromptState("system", "next"),
                new Memory(),
                agent.getAvailableTools(),
                1);

        Assert.assertEquals(ToolChoice.NONE, turn.toolChoice());
        Assert.assertEquals(0, turn.exposedTools().toolCount());
        agent.activateTurnTools(turn.exposedTools());

        String observation = agent.executeTool(toolCall(
                "provider-ignored-none", "guarded_tool", "{}"));

        Assert.assertTrue(observation.contains("not exposed or allowed"));
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldDenyBusinessToolAtDispatcherDuringNoneTodoStep() {
        AtomicInteger executions = new AtomicInteger();
        AgentContext context = AgentContext.builder()
                .requestId("none-step-dispatch-boundary")
                .query("核对参数后调用工具")
                .executionProfile(AgentExecutionProfile.DEEP)
                .productFiles(List.of())
                .agentRunState(AgentRunState.builder().build())
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection tools = new ToolCollection();
        tools.addTool(todoWriteTool);
        tools.addTool(countingTool("guarded_tool", executions, false));
        tools.setAgentContext(context);
        TestAgent agent = new TestAgent();
        agent.setName("none-step-dispatch-boundary");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        todoWriteTool.execute(Map.of(
                "command", "create",
                "title", "分步执行",
                "steps", List.of("核对参数", "调用工具"),
                "evidence_policies", List.of("NONE", "TOOL")
        ));

        ContextPipeline.PreparedModelTurn turn = new ContextPipeline().prepareTurn(
                context,
                new ReactorConfig(),
                new ContextPipeline.PromptState("system", "next"),
                new Memory(),
                tools,
                2);
        agent.activateTurnTools(turn.exposedTools());
        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "provider-tried-future-tool", "guarded_tool", "{}"));

        Assert.assertFalse(outcome.isSuccess());
        Assert.assertTrue(outcome.getErrorMsg().contains("not exposed or allowed"));
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldRecordStableHashedOperationKeysWithoutPlaintext() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = testAgent(executions, "调用测试工具");

        agent.executeTool(toolCall("call-1", "guarded_tool", "{\"value\":1}"));
        agent.executeTool(toolCall("call-2", "guarded_tool", "{\"value\":1}"));
        agent.executeTool(toolCall("call-3", "guarded_tool", "{\"value\":2}"));

        var evidence = agent.getContext().snapshotToolExecutionEvidence();
        Assert.assertEquals(3, evidence.size());
        Assert.assertEquals(evidence.get(0).getOperationKey(), evidence.get(1).getOperationKey());
        Assert.assertNotEquals(evidence.get(0).getOperationKey(), evidence.get(2).getOperationKey());
        Assert.assertTrue(evidence.get(0).getOperationKey().matches("sha256:[0-9a-f]{64}"));
        Assert.assertFalse(evidence.get(0).getOperationKey().contains("guarded_tool"));
        Assert.assertFalse(evidence.get(0).getOperationKey().contains("value"));
        Assert.assertEquals(2, executions.get());
    }

    @Test
    public void shouldRejectInvalidBuiltInArgumentsBeforePermissionOrExecution() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger permissionChecks = new AtomicInteger();
        TestAgent agent = testAgent(executions, "调用测试工具", strictObjectSchema());
        agent.setPermissionPolicy((toolName, input, activeTools, context) -> {
            permissionChecks.incrementAndGet();
            return PermissionPolicy.PermissionDecision.allow();
        });

        List<ToolExecutionOutcome> outcomes = List.of(
                agent.executeOutcome(toolCall("not-object", "guarded_tool", "[]")),
                agent.executeOutcome(toolCall("missing-required", "guarded_tool", "{\"query\":\"hello\"}")),
                agent.executeOutcome(toolCall(
                        "wrong-property-type", "guarded_tool", "{\"query\":\"hello\",\"limit\":\"two\"}")),
                agent.executeOutcome(toolCall(
                        "unexpected-property", "guarded_tool",
                        "{\"query\":\"hello\",\"limit\":2,\"unexpected\":true}")));

        Assert.assertTrue(outcomes.stream().noneMatch(ToolExecutionOutcome::isSuccess));
        Assert.assertTrue(outcomes.get(0).getErrorMsg().contains("must be type 'object'"));
        Assert.assertTrue(outcomes.get(1).getErrorMsg().contains("missing required property 'limit'"));
        Assert.assertTrue(outcomes.get(2).getErrorMsg().contains("must be type 'integer'"));
        Assert.assertTrue(outcomes.get(3).getErrorMsg().contains("unexpected property 'unexpected'"));
        Assert.assertEquals(0, permissionChecks.get());
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldExecuteBuiltInToolAfterSchemaAndPermissionPass() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger permissionChecks = new AtomicInteger();
        TestAgent agent = testAgent(executions, "调用测试工具", strictObjectSchema());
        agent.setPermissionPolicy((toolName, input, activeTools, context) -> {
            permissionChecks.incrementAndGet();
            return PermissionPolicy.PermissionDecision.allow();
        });

        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "valid-input", "guarded_tool", "{\"query\":\"hello\",\"limit\":2}"));

        Assert.assertTrue(outcome.isSuccess());
        Assert.assertEquals(1, permissionChecks.get());
        Assert.assertEquals(1, executions.get());
    }

    @Test
    public void shouldRejectMalformedJsonBeforePermissionOrExecution() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger permissionChecks = new AtomicInteger();
        TestAgent agent = testAgent(executions, "调用测试工具", strictObjectSchema());
        agent.setPermissionPolicy((toolName, input, activeTools, context) -> {
            permissionChecks.incrementAndGet();
            return PermissionPolicy.PermissionDecision.allow();
        });

        ToolExecutionOutcome outcome = agent.executeOutcome(
                toolCall("malformed-json", "guarded_tool", "{not-json"));

        Assert.assertFalse(outcome.isSuccess());
        Assert.assertTrue(outcome.getErrorMsg().contains("invalid tool arguments"));
        Assert.assertEquals(0, permissionChecks.get());
        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void shouldApplyObjectRequiredTypeAndAdditionalPropertyValidationToMcpTools() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        TestAgent agent = mcpAgent(executor, toolInfo);

        List<ToolExecutionOutcome> outcomes = List.of(
                agent.executeOutcome(toolCall("mcp-not-object", toolInfo.resolveExposedName(), "[]")),
                agent.executeOutcome(toolCall("mcp-missing", toolInfo.resolveExposedName(), "{}")),
                agent.executeOutcome(toolCall(
                        "mcp-wrong-type", toolInfo.resolveExposedName(), "{\"query\":42,\"limit\":2}")),
                agent.executeOutcome(toolCall(
                        "mcp-extra", toolInfo.resolveExposedName(),
                        "{\"query\":\"hello\",\"limit\":2,\"unexpected\":true}")));

        Assert.assertTrue(outcomes.stream().noneMatch(ToolExecutionOutcome::isSuccess));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldExecuteMcpToolAfterServerSideSchemaValidationPasses() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.text("remote-ok"));
        TestAgent agent = mcpAgent(executor, toolInfo);

        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "mcp-valid", toolInfo.resolveExposedName(),
                "{\"query\":\"hello\",\"limit\":2}"));

        Assert.assertTrue(outcome.isSuccess());
        Assert.assertEquals("remote-ok", outcome.getToolResult());
        Mockito.verify(executor).executeTool(Mockito.eq(toolInfo), Mockito.any());
    }

    @Test
    public void shouldNormalizeUniqueRemoteMcpNameWithinActiveTurnView() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.text("remote-ok"));
        TestAgent agent = mcpAgent(executor, toolInfo);
        ToolCall call = toolCall(
                "mcp-remote-alias", toolInfo.getName(),
                "{\"query\":\"hello\",\"limit\":2}");

        ToolExecutionOutcome outcome = agent.executeOutcome(call);

        Assert.assertTrue(outcome.isSuccess());
        Assert.assertEquals(toolInfo.resolveExposedName(), call.getFunction().getName());
        Assert.assertEquals(toolInfo.resolveExposedName(),
                agent.getContext().snapshotToolExecutionEvidence().get(0).getToolName());
        Mockito.verify(executor).executeTool(Mockito.eq(toolInfo), Mockito.any());
    }

    @Test
    public void shouldNotResolveRemoteAliasForToolHiddenFromActiveTurn() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        TestAgent agent = mcpAgent(executor, toolInfo);
        agent.activateTurnTools(agent.getAvailableTools().selectedView(List.of()));

        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "mcp-hidden-alias", toolInfo.getName(),
                "{\"query\":\"hello\",\"limit\":2}"));

        Assert.assertFalse(outcome.isSuccess());
        Assert.assertTrue(outcome.getErrorMsg().contains("not exposed or allowed"));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void permissiveCustomPolicyShouldNotBypassHiddenDirectToolBoundary() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        TestAgent agent = mcpAgent(executor, toolInfo);
        agent.activateTurnTools(agent.getAvailableTools().selectedView(List.of()));
        AtomicInteger permissionChecks = new AtomicInteger();
        agent.setPermissionPolicy((toolName, input, activeTools, context) -> {
            permissionChecks.incrementAndGet();
            return PermissionPolicy.PermissionDecision.allow();
        });

        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "hidden-direct-canonical", toolInfo.resolveExposedName(),
                "{\"query\":\"hello\",\"limit\":2}"));

        Assert.assertFalse(outcome.isSuccess());
        Assert.assertTrue(outcome.getErrorMsg().contains("not exposed or allowed"));
        Assert.assertEquals(0, permissionChecks.get());
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldRejectAmbiguousRemoteMcpNameAlias() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo first = strictMcpToolInfo("mcp__remote_a__search");
        McpToolInfo second = strictMcpToolInfo("mcp__remote_b__search");
        TestAgent agent = mcpAgent(executor, first, second);

        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "mcp-ambiguous-alias", "search",
                "{\"query\":\"hello\",\"limit\":2}"));

        Assert.assertFalse(outcome.isSuccess());
        Assert.assertTrue(outcome.getErrorMsg().contains("not exposed or allowed"));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldFailClosedWhenMcpSchemaIsMalformed() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = McpToolInfo.builder()
                .mcpId("remote")
                .name("broken")
                .exposedName("mcp__remote__broken")
                .desc("broken schema")
                .parameters("{not-json")
                .build();
        TestAgent agent = mcpAgent(executor, toolInfo);

        ToolExecutionOutcome outcome = agent.executeOutcome(toolCall(
                "mcp-broken-schema", toolInfo.resolveExposedName(), "{}"));

        Assert.assertFalse(outcome.isSuccess());
        Assert.assertTrue(outcome.getErrorMsg().contains("Tool schema is not valid JSON"));
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void discoveredDeferredProxyShouldUseRealTargetAcrossHarnessBoundaries() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.text("remote-ok"));
        TestAgent agent = mcpProxyAgent(executor, toolInfo);
        agent.getContext().getAgentRunState().markToolsDiscovered(List.of(toolInfo.resolveExposedName()));

        AtomicReference<String> permissionToolName = new AtomicReference<>();
        AtomicReference<Object> permissionInput = new AtomicReference<>();
        agent.setPermissionPolicy((toolName, input, activeTools, context) -> {
            permissionToolName.set(toolName);
            permissionInput.set(input);
            Assert.assertTrue(activeTools.getMcpToolMap().containsKey(toolInfo.resolveExposedName()));
            return PermissionPolicy.PermissionDecision.allow();
        });
        List<HookBus.HookEvent> hookEvents = new CopyOnWriteArrayList<>();
        HookBus hookBus = new HookBus();
        hookBus.register(event -> {
            if (event.point() == HookBus.HookPoint.PRE_TOOL
                    || event.point() == HookBus.HookPoint.POST_TOOL) {
                hookEvents.add(event);
            }
            return HookBus.HookDecision.allow();
        });
        agent.setHookBus(hookBus);

        ToolCall firstCall = proxyCall(
                "proxy-call-1", toolInfo.resolveExposedName(), "{\"query\":\"hello\",\"limit\":2}");
        ToolCall secondCall = proxyCall(
                "proxy-call-2", toolInfo.resolveExposedName(), "{\"limit\":2,\"query\":\"hello\"}");
        ToolExecutionOutcome first = agent.executeOutcome(firstCall);
        ToolExecutionOutcome reused = agent.executeOutcome(secondCall);

        Assert.assertTrue(first.isSuccess());
        Assert.assertTrue(reused.isSuccess());
        Assert.assertTrue(reused.isReused());
        Assert.assertEquals(toolInfo.resolveExposedName(), firstCall.getFunction().getName());
        Assert.assertEquals(toolInfo.resolveExposedName(), secondCall.getFunction().getName());
        Assert.assertEquals(toolInfo.resolveExposedName(), permissionToolName.get());
        Assert.assertEquals(Map.of("query", "hello", "limit", 2), permissionInput.get());
        Assert.assertEquals(4, hookEvents.size());
        Assert.assertTrue(hookEvents.stream().allMatch(
                event -> toolInfo.resolveExposedName().equals(event.capability())));
        Mockito.verify(executor).executeTool(Mockito.eq(toolInfo), Mockito.any());

        var evidence = agent.getContext().snapshotToolExecutionEvidence();
        Assert.assertEquals(2, evidence.size());
        Assert.assertEquals("proxy-call-1", evidence.get(0).getToolCallId());
        Assert.assertEquals("proxy-call-2", evidence.get(1).getToolCallId());
        Assert.assertEquals(toolInfo.resolveExposedName(), evidence.get(0).getToolName());
        Assert.assertEquals(toolInfo.resolveExposedName(), evidence.get(1).getToolName());
        Assert.assertEquals(evidence.get(0).getOperationKey(), evidence.get(1).getOperationKey());

        DefaultEvidenceValidator.ValidationResult completion = new DefaultEvidenceValidator().validate(
                CompletionRequest.builder()
                        .requiredToolName(toolInfo.resolveExposedName())
                        .toolEvidence(evidence)
                        .build());
        Assert.assertTrue(completion.reasons().toString(), completion.reasons().isEmpty());
    }

    @Test
    public void explicitDeferredContractShouldAuthorizeProxyWithoutSearchAndRecordMcpProvider() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.text("remote-ok"));
        TestAgent agent = mcpProxyAgent(executor, toolInfo);
        ToolInvocationContract contract = ToolInvocationContract.resolve(
                "必须且只能调用 MCP 工具 search 一次",
                List.of(toolInfo.resolveExposedName(), ExecuteExtraTool.NAME));
        Assert.assertEquals(
                java.util.Set.of(toolInfo.resolveExposedName()),
                contract.requiredToolNames());
        agent.getContext().setToolInvocationContract(contract);

        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.createToolInvocations(Mockito.any()))
                .thenReturn(Map.of("outer-proxy-id", 701L));
        agent.getContext().setExecutionRecorder(recorder);
        agent.getContext().activateLedgerRun(77L, "run-proxy-77");
        agent.getContext().getAgentRunState().bindCurrentLlmInvocationId(99L);

        ToolExecutionOutcome outcome = agent.executeOutcome(proxyCall(
                "outer-proxy-id", "search", "{\"query\":\"hello\",\"limit\":2}"));

        Assert.assertTrue(outcome.getErrorMsg(), outcome.isSuccess());
        Assert.assertEquals(toolInfo.resolveExposedName(),
                agent.getContext().snapshotToolExecutionEvidence().get(0).getToolName());
        DefaultEvidenceValidator.ValidationResult completion = new DefaultEvidenceValidator().validate(
                CompletionRequest.builder()
                        .requiredToolName(toolInfo.resolveExposedName())
                        .toolInvocationContract(contract)
                        .toolEvidence(agent.getContext().snapshotToolExecutionEvidence())
                        .build());
        Assert.assertTrue(completion.reasons().toString(), completion.reasons().isEmpty());

        ArgumentCaptor<ToolInvocationBatchStartRecord> startCaptor =
                ArgumentCaptor.forClass(ToolInvocationBatchStartRecord.class);
        Mockito.verify(recorder).createToolInvocations(startCaptor.capture());
        ToolInvocationBatchStartRecord.Item started = startCaptor.getValue().getItems().get(0);
        Assert.assertEquals("outer-proxy-id", started.getToolCallId());
        Assert.assertEquals(toolInfo.resolveExposedName(), started.getToolName());
        Assert.assertEquals(ExecutionLedgerConstants.TOOL_PROVIDER_MCP, started.getToolProvider());
        Assert.assertEquals("{\"query\":\"hello\",\"limit\":2}", started.getInputJson());

        ArgumentCaptor<ToolInvocationFinishRecord> finishCaptor =
                ArgumentCaptor.forClass(ToolInvocationFinishRecord.class);
        Mockito.verify(recorder).finishToolInvocation(finishCaptor.capture());
        Assert.assertEquals("outer-proxy-id", finishCaptor.getValue().getToolCallId());
        Assert.assertEquals(toolInfo.resolveExposedName(), finishCaptor.getValue().getToolName());
    }

    @Test
    public void deferredProxyShouldRejectUndiscoveredHallucinatedRecursiveAndInvalidNativeInput() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = strictMcpToolInfo("mcp__remote__search");
        TestAgent agent = mcpProxyAgent(executor, toolInfo);

        ToolExecutionOutcome undiscovered = agent.executeOutcome(proxyCall(
                "proxy-undiscovered", toolInfo.resolveExposedName(),
                "{\"query\":\"hello\",\"limit\":2}"));
        ToolExecutionOutcome hallucinated = agent.executeOutcome(proxyCall(
                "proxy-hallucinated", "mcp__remote__does_not_exist", "{}"));
        ToolExecutionOutcome recursive = agent.executeOutcome(proxyCall(
                "proxy-recursive", ExecuteExtraTool.NAME, "{}"));

        agent.getContext().getAgentRunState().markToolsDiscovered(List.of(toolInfo.resolveExposedName()));
        AtomicInteger permissionChecks = new AtomicInteger();
        agent.setPermissionPolicy((toolName, input, activeTools, context) -> {
            permissionChecks.incrementAndGet();
            return PermissionPolicy.PermissionDecision.allow();
        });
        ToolExecutionOutcome invalidNativeInput = agent.executeOutcome(proxyCall(
                "proxy-invalid-native", toolInfo.resolveExposedName(), "{\"query\":\"hello\"}"));

        Assert.assertFalse(undiscovered.isSuccess());
        Assert.assertTrue(undiscovered.getErrorMsg().contains("has not been discovered"));
        Assert.assertFalse(hallucinated.isSuccess());
        Assert.assertTrue(hallucinated.getErrorMsg().contains("was not found"));
        Assert.assertFalse(recursive.isSuccess());
        Assert.assertTrue(recursive.getErrorMsg().contains("cannot recursively invoke itself"));
        Assert.assertFalse(invalidNativeInput.isSuccess());
        Assert.assertTrue(invalidNativeInput.getErrorMsg().contains("missing required property 'limit'"));
        Assert.assertEquals(0, permissionChecks.get());
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    public void shouldReuseCanonicalSuccessfulOperationBeforeRepeatingSideEffect() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = localAgent(new BaseTool() {
            @Override
            public String getName() {
                return "side_effect_tool";
            }

            @Override
            public String getDescription() {
                return "side effect test tool";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                return "execution-" + executions.incrementAndGet();
            }
        });

        ToolExecutionOutcome first = agent.executeOutcome(toolCall(
                "side-effect-first", "side_effect_tool", "{\"value\":1,\"mode\":\"safe\"}"));
        ToolExecutionOutcome second = agent.executeOutcome(toolCall(
                "side-effect-second", "side_effect_tool", "{ \"mode\" : \"safe\", \"value\" : 1 }"));

        Assert.assertTrue(first.isSuccess());
        Assert.assertFalse(first.isReused());
        Assert.assertTrue(second.isSuccess());
        Assert.assertTrue(second.isReused());
        Assert.assertTrue(second.getLlmObservation().contains("Reused prior successful result"));
        Assert.assertEquals("execution-1", second.getToolResult());
        Assert.assertEquals(1, executions.get());
    }

    @Test
    public void shouldStampTodoActivationAndRejectReusedResultForNextToolStep() {
        AtomicInteger executions = new AtomicInteger();
        AgentContext context = AgentContext.builder()
                .requestId("todo-scope-dispatch")
                .query("连续执行两个工具核验步骤")
                .executionProfile(AgentExecutionProfile.DEEP)
                .productFiles(List.of())
                .agentRunState(AgentRunState.builder().build())
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection tools = new ToolCollection();
        tools.addTool(todoWriteTool);
        tools.addTool(countingTool("scoped_tool", executions, false));
        tools.setAgentContext(context);
        TestAgent agent = new TestAgent();
        agent.setName("todo-scope-dispatch");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        todoWriteTool.execute(Map.of(
                "command", "create",
                "title", "两步工具核验",
                "steps", List.of("第一步", "第二步"),
                "evidence_policies", List.of("TOOL", "TOOL")
        ));

        ToolExecutionOutcome first = agent.executeOutcome(toolCall(
                "scoped-call-1", "scoped_tool", "{\"value\":1}"));
        var firstEvidence = context.snapshotToolExecutionEvidence().get(0);
        Assert.assertTrue(first.isSuccess());
        Assert.assertEquals(Integer.valueOf(0), firstEvidence.getTodoStepIndex());
        Assert.assertEquals(Long.valueOf(1L), firstEvidence.getTodoStepActivationId());
        Assert.assertFalse(firstEvidence.isReused());
        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "evidence_refs", List.of("scoped-call-1")
        ));

        ToolExecutionOutcome reused = agent.executeOutcome(toolCall(
                "scoped-call-2", "scoped_tool", "{ \"value\" : 1 }"));
        var reusedEvidence = context.snapshotToolExecutionEvidence().get(1);
        Assert.assertTrue(reused.isReused());
        Assert.assertEquals(Integer.valueOf(1), reusedEvidence.getTodoStepIndex());
        Assert.assertEquals(Long.valueOf(2L), reusedEvidence.getTodoStepActivationId());
        Assert.assertTrue(reusedEvidence.isReused());
        Assert.assertEquals(1, executions.get());

        try {
            todoWriteTool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 1,
                    "step_status", "completed",
                    "evidence_refs", List.of("scoped-call-2")
            ));
            Assert.fail("a reused operation result must not prove a new Todo item");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("non-reused"));
        }
    }

    @Test
    public void shouldExecuteAgainAfterIdenticalOperationFailed() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = localAgent(new BaseTool() {
            @Override
            public String getName() {
                return "retry_after_failure_tool";
            }

            @Override
            public String getDescription() {
                return "fails once";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                if (executions.incrementAndGet() == 1) {
                    return ToolResultPayload.failure("failed", "failed", null, "transient failure");
                }
                return ToolResultPayload.text("recovered");
            }
        });

        ToolExecutionOutcome failed = agent.executeOutcome(toolCall(
                "failure-first", "retry_after_failure_tool", "{\"value\":1}"));
        ToolExecutionOutcome recovered = agent.executeOutcome(toolCall(
                "failure-second", "retry_after_failure_tool", "{\"value\":1}"));

        Assert.assertFalse(failed.isSuccess());
        Assert.assertTrue(recovered.isSuccess());
        Assert.assertFalse(recovered.isReused());
        Assert.assertEquals("recovered", recovered.getToolResult());
        Assert.assertEquals(2, executions.get());
    }

    @Test
    public void shouldExecuteSameToolAgainWhenCanonicalArgumentsDiffer() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = localAgent(countingTool("argument_sensitive_tool", executions, false));

        ToolExecutionOutcome first = agent.executeOutcome(toolCall(
                "different-args-first", "argument_sensitive_tool", "{\"value\":1}"));
        ToolExecutionOutcome second = agent.executeOutcome(toolCall(
                "different-args-second", "argument_sensitive_tool", "{\"value\":2}"));

        Assert.assertTrue(first.isSuccess());
        Assert.assertTrue(second.isSuccess());
        Assert.assertFalse(second.isReused());
        Assert.assertEquals(2, executions.get());
    }

    @Test
    public void shouldAllowLocalToolToOptOutOfSuccessfulOperationReuse() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = localAgent(countingTool("polling_tool", executions, true));

        ToolExecutionOutcome first = agent.executeOutcome(toolCall(
                "poll-first", "polling_tool", "{\"cursor\":\"same\"}"));
        ToolExecutionOutcome second = agent.executeOutcome(toolCall(
                "poll-second", "polling_tool", "{\"cursor\":\"same\"}"));

        Assert.assertTrue(first.isSuccess());
        Assert.assertTrue(second.isSuccess());
        Assert.assertFalse(second.isReused());
        Assert.assertEquals(2, executions.get());
    }

    @Test
    public void shouldAllowMcpToolToOptOutOfSuccessfulOperationReuse() {
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        McpToolInfo toolInfo = McpToolInfo.builder()
                .mcpId("remote")
                .name("poll")
                .exposedName("mcp__remote__poll")
                .desc("poll remote state")
                .parameters("{\"type\":\"object\"}")
                .allowRepeatedSuccessfulCall(true)
                .build();
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.text("remote-state"));
        TestAgent agent = mcpAgent(executor, toolInfo);

        ToolExecutionOutcome first = agent.executeOutcome(toolCall(
                "mcp-poll-first", toolInfo.resolveExposedName(), "{\"cursor\":\"same\"}"));
        ToolExecutionOutcome second = agent.executeOutcome(toolCall(
                "mcp-poll-second", toolInfo.resolveExposedName(), "{\"cursor\":\"same\"}"));

        Assert.assertTrue(first.isSuccess());
        Assert.assertTrue(second.isSuccess());
        Assert.assertFalse(second.isReused());
        Mockito.verify(executor, Mockito.times(2)).executeTool(Mockito.eq(toolInfo), Mockito.any());
    }

    @Test
    public void shouldCountReusedOperationsAgainstModelRequestedToolCallBudget() {
        AtomicInteger executions = new AtomicInteger();
        TestAgent agent = localAgent(countingTool("budgeted_tool", executions, false));
        agent.setRunBudget(new AgentRunBudget(10, 2, 3, 60_000L, 10_000L, 10_000L));

        ToolExecutionOutcome first = agent.executeOutcome(toolCall(
                "budget-first", "budgeted_tool", "{\"value\":1}"));
        ToolExecutionOutcome reused = agent.executeOutcome(toolCall(
                "budget-reused", "budgeted_tool", "{\"value\":1}"));
        ToolExecutionOutcome rejected = agent.executeOutcome(toolCall(
                "budget-rejected", "budgeted_tool", "{\"value\":1}"));

        Assert.assertTrue(first.isSuccess());
        Assert.assertTrue(reused.isSuccess());
        Assert.assertTrue(reused.isReused());
        Assert.assertFalse(rejected.isSuccess());
        Assert.assertTrue(rejected.getErrorMsg().contains("budget exceeded"));
        Assert.assertEquals(1, executions.get());
        Assert.assertEquals(2, agent.getContext().getAgentRunState().getToolCallCountValue());
    }

    private TestAgent testAgent(AtomicInteger executions, String query) {
        return testAgent(executions, query, Map.of("type", "object"));
    }

    private TestAgent testAgent(AtomicInteger executions,
                                String query,
                                Map<String, Object> schema) {
        ToolCollection tools = new ToolCollection();
        tools.addTool(new BaseTool() {
            @Override
            public String getName() {
                return "guarded_tool";
            }

            @Override
            public String getDescription() {
                return "test tool";
            }

            @Override
            public Map<String, Object> toParams() {
                return schema;
            }

            @Override
            public Object execute(Object input) {
                executions.incrementAndGet();
                return "ok";
            }
        });
        AgentContext context = AgentContext.builder()
                .requestId("tool-boundary-test")
                .query(query)
                .productFiles(List.of())
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        TestAgent agent = new TestAgent();
        agent.setName("tool-boundary-test");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        return agent;
    }

    private TestAgent mcpAgent(McpToolExecutor executor, McpToolInfo... toolInfos) {
        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        for (McpToolInfo toolInfo : toolInfos) {
            tools.addMcpTool(toolInfo);
        }
        AgentContext context = AgentContext.builder()
                .requestId("mcp-tool-boundary-test")
                .query("调用远程测试工具")
                .online(true)
                .productFiles(List.of())
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        TestAgent agent = new TestAgent();
        agent.setName("mcp-tool-boundary-test");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        return agent;
    }

    private TestAgent mcpProxyAgent(McpToolExecutor executor, McpToolInfo toolInfo) {
        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        tools.addTool(new ExecuteExtraTool());
        tools.addMcpTool(toolInfo);
        AgentContext context = AgentContext.builder()
                .requestId("mcp-proxy-boundary-test")
                .sessionId("mcp-proxy-session")
                .query("调用 deferred MCP 测试工具")
                .online(true)
                .productFiles(List.of())
                .agentRunState(AgentRunState.builder().build())
                .toolCollection(tools)
                .build();
        tools.setAgentContext(context);
        TestAgent agent = new TestAgent();
        agent.setName("mcp-proxy-boundary-test");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        agent.activateTurnTools(tools.selectedView(List.of(ExecuteExtraTool.NAME)));
        return agent;
    }

    private TestAgent localAgent(BaseTool tool) {
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool);
        AgentContext context = AgentContext.builder()
                .requestId("operation-reuse-test")
                .query("调用测试工具")
                .productFiles(List.of())
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        TestAgent agent = new TestAgent();
        agent.setName("operation-reuse-test");
        agent.setContext(context);
        agent.setAvailableTools(tools);
        return agent;
    }

    private BaseTool countingTool(String name,
                                  AtomicInteger executions,
                                  boolean allowRepeatedSuccessfulCall) {
        return new BaseTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "counting test tool";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                return "execution-" + executions.incrementAndGet();
            }

            @Override
            public boolean allowRepeatedSuccessfulCall() {
                return allowRepeatedSuccessfulCall;
            }
        };
    }

    private Map<String, Object> strictObjectSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "limit", Map.of("type", "integer")),
                "required", List.of("query", "limit"),
                "additionalProperties", false);
    }

    private McpToolInfo strictMcpToolInfo(String exposedName) {
        return McpToolInfo.builder()
                .mcpId("remote")
                .name("search")
                .exposedName(exposedName)
                .desc("remote search")
                .parameters("""
                        {"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query","limit"],"additionalProperties":false}
                        """)
                .build();
    }

    private ToolCall toolCall(String id, String name, String arguments) {
        return ToolCall.builder()
                .id(id)
                .function(ToolCall.Function.builder()
                        .name(name)
                        .arguments(arguments)
                        .build())
                .build();
    }

    private ToolCall proxyCall(String id, String targetToolName, String targetArguments) {
        return toolCall(
                id,
                ExecuteExtraTool.NAME,
                "{\"tool_name\":\"" + targetToolName + "\",\"params\":" + targetArguments + "}");
    }

    private static final class TestAgent extends BaseAgent {

        @Override
        public String step() {
            return "unused";
        }

        private void activateTurnTools(ToolCollection tools) {
            activateToolsForTurn(tools);
        }

        private ToolExecutionOutcome executeOutcome(ToolCall command) {
            return executeToolOutcome(command);
        }
    }
}
