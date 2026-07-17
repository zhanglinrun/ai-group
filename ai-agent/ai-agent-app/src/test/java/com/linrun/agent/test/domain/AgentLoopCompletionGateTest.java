package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.completion.CompletionDecision;
import com.linrun.agent.domain.agent.runtime.completion.CompletionGate;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.loop.ModelGateway;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;

import java.util.ArrayList;
import java.util.List;

public class AgentLoopCompletionGateTest {

    @Test
    public void shouldContinueSameLoopAfterCompletionGateRejectsDraft() {
        AgentLoop agent = newAgent();
        CompletionGate gate = Mockito.mock(CompletionGate.class);
        Mockito.when(gate.evaluate(Mockito.any()))
                .thenReturn(CompletionDecision.builder()
                        .canStop(false)
                        .reasons(List.of("todo remains"))
                        .requiredActions(List.of("continue current todo"))
                        .build())
                .thenReturn(CompletionDecision.allow(true));
        agent.setCompletionGate(gate);
        agent.setToolCalls(List.of());
        agent.getMemory().addMessage(Message.assistantMessage("premature draft", null));

        String feedback = ReflectionTestUtils.invokeMethod(agent, "executeModelTurn");

        Assert.assertEquals(AgentState.IDLE, agent.getState());
        Assert.assertTrue(feedback.contains("todo remains"));
        Assert.assertTrue(agent.getMemory().getLastMessage().getContent().contains("continue current todo"));

        agent.getMemory().addMessage(Message.assistantMessage("verified final answer", null));
        String finalAnswer = ReflectionTestUtils.invokeMethod(agent, "executeModelTurn");

        Assert.assertEquals("verified final answer", finalAnswer);
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
        Mockito.verify(gate, Mockito.times(2)).evaluate(Mockito.any());
    }

    @Test
    public void shouldRouteModelTurnsThroughModelGateway() throws Exception {
        AgentLoop agent = newAgent();
        ModelGateway gateway = Mockito.mock(ModelGateway.class);
        Mockito.when(gateway.functionCallType()).thenReturn("function");
        Mockito.when(gateway.complete(Mockito.any()))
                .thenReturn(new ModelGateway.ModelTurnResponse("gateway answer", List.of()));
        agent.setModelGateway(gateway);

        Boolean completed = ReflectionTestUtils.invokeMethod(agent, "runModelTurn");

        Assert.assertTrue(Boolean.TRUE.equals(completed));
        Assert.assertEquals("gateway answer", agent.getMemory().getLastMessage().getContent());
        Mockito.verify(gateway).complete(Mockito.any());
    }

    @Test
    public void shouldRejectMaxTokenModelFinishBeforeCompletionGate() throws Exception {
        AgentLoop agent = newAgent();
        ModelGateway gateway = Mockito.mock(ModelGateway.class);
        Mockito.when(gateway.functionCallType()).thenReturn("function");
        Mockito.when(gateway.complete(Mockito.any()))
                .thenReturn(ModelGateway.ModelTurnResponse.fromProvider(
                        "plausible but truncated final answer", List.of(), "max_tokens"));
        agent.setModelGateway(gateway);

        Boolean completed = ReflectionTestUtils.invokeMethod(agent, "runModelTurn");

        Assert.assertFalse(Boolean.TRUE.equals(completed));
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
        Assert.assertEquals(AgentStopReason.MODEL_MAX_TOKENS, agent.getStopReason());
        Assert.assertTrue(agent.getContext().isRunFailed());
        Assert.assertTrue(agent.getMemory().getMessages().isEmpty());
    }

    @Test
    public void shouldRejectRefusalAndContentFilterAsTypedModelStops() throws Exception {
        assertRejectedFinishReason("refusal", AgentStopReason.MODEL_REFUSAL);
        assertRejectedFinishReason("content_filter", AgentStopReason.MODEL_CONTENT_FILTER);
    }

    @Test
    public void shouldStopImmediatelyWhenFinalModelCallCrossesTokenBudget() throws Exception {
        AgentLoop agent = newAgent();
        agent.setRunBudget(agent.getRunBudget().withMaxTotalTokens(10));
        ModelGateway gateway = Mockito.mock(ModelGateway.class);
        Mockito.when(gateway.functionCallType()).thenReturn("function");
        Mockito.when(gateway.complete(Mockito.any())).thenAnswer(invocation -> {
            agent.getContext().getAgentRunState().recordLlmUsage(11, 0);
            return ModelGateway.ModelTurnResponse.fromProvider(
                    "otherwise acceptable final answer", List.of(), "stop");
        });
        agent.setModelGateway(gateway);

        Boolean completed = ReflectionTestUtils.invokeMethod(agent, "runModelTurn");

        Assert.assertFalse(Boolean.TRUE.equals(completed));
        Assert.assertEquals(AgentStopReason.TOKEN_BUDGET, agent.getStopReason());
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
        Assert.assertTrue(agent.getMemory().getMessages().isEmpty());
    }

    @Test
    public void shouldStopImmediatelyWhenFinalModelCallCrossesCreditBudget() throws Exception {
        AgentLoop agent = newAgent();
        agent.setRunBudget(agent.getRunBudget().withMaxMicrocredits(10));
        ModelGateway gateway = Mockito.mock(ModelGateway.class);
        Mockito.when(gateway.functionCallType()).thenReturn("function");
        Mockito.when(gateway.complete(Mockito.any())).thenAnswer(invocation -> {
            agent.getContext().getAgentRunState().recordLlmUsage(0, 11);
            return ModelGateway.ModelTurnResponse.fromProvider(
                    "otherwise acceptable final answer", List.of(), "stop");
        });
        agent.setModelGateway(gateway);

        Boolean completed = ReflectionTestUtils.invokeMethod(agent, "runModelTurn");

        Assert.assertFalse(Boolean.TRUE.equals(completed));
        Assert.assertEquals(AgentStopReason.CREDIT_BUDGET, agent.getStopReason());
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
        Assert.assertTrue(agent.getMemory().getMessages().isEmpty());
    }

    private void assertRejectedFinishReason(String rawFinishReason,
                                            AgentStopReason expectedStopReason) throws Exception {
        AgentLoop agent = newAgent();
        ModelGateway gateway = Mockito.mock(ModelGateway.class);
        Mockito.when(gateway.functionCallType()).thenReturn("function");
        Mockito.when(gateway.complete(Mockito.any()))
                .thenReturn(ModelGateway.ModelTurnResponse.fromProvider(
                        "provider did not complete normally", List.of(), rawFinishReason));
        agent.setModelGateway(gateway);

        Boolean completed = ReflectionTestUtils.invokeMethod(agent, "runModelTurn");

        Assert.assertFalse(Boolean.TRUE.equals(completed));
        Assert.assertEquals(expectedStopReason, agent.getStopReason());
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
        Assert.assertTrue(agent.getContext().isRunFailed());
    }

    private AgentLoop newAgent() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "agentLoopMaxTurns", 4);
        ReflectionTestUtils.setField(config, "agentLoopMaxToolCalls", 8);
        ReflectionTestUtils.setField(config, "agentLoopMaxCompletionAttempts", 3);
        ReflectionTestUtils.setField(config, "agentLoopModelName", "test-model");
        ReflectionTestUtils.setField(config, "maxObserve", "1000");
        ReflectionTestUtils.setField(config, "toolMaxAttempts", 1);
        config.setLLMSettingsMap("{\"test-model\":{\"model\":\"test-model\",\"apikey\":\"test-key\","
                + "\"base_url\":\"http://localhost\",\"max_tokens\":1000,\"max_input_tokens\":4000}}");

        ToolCollection tools = new ToolCollection();
        AgentContext context = AgentContext.builder()
                .requestId("completion-loop-request")
                .sessionId("completion-loop-session")
                .query("完成复杂任务")
                .printer(Mockito.mock(Printer.class))
                .dateInfo("")
                .basePrompt("")
                .historyDialogue("")
                .toolCollection(tools)
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(ReactorRuntimeTestSupport.runtimeDependencies(config))
                .build();
        tools.setAgentContext(context);
        return new AgentLoop(context);
    }
}
