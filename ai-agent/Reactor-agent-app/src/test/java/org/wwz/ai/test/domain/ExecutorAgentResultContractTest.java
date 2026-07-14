package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ExecutorAgent;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolChoice;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.test.domain.support.ReactorRuntimeTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ExecutorAgentResultContractTest {

    @Test
    public void shouldReturnActualExecutorResultBeforeCompletionMarker() {
        ExecutorAgent agent = newExecutorAgent();
        agent.setToolCalls(List.of());
        agent.getMemory().addMessage(Message.assistantMessage(
                "已使用 ConcurrentHashMap，并给出过期与清理策略。", null));

        String result = agent.act();

        Assert.assertTrue(result, result.startsWith("已使用 ConcurrentHashMap"));
        Assert.assertTrue(result, result.endsWith("当前 task 完成，请推进计划"));
    }

    @Test
    public void shouldSkipOptionalDigitalEmployeeCallWhenPromptIsBlank() {
        ExecutorAgent agent = newExecutorAgent();
        LLM llm = Mockito.mock(LLM.class);
        agent.setLlm(llm);

        agent.generateDigitalEmployee("核验缓存设计");

        Assert.assertEquals("核验缓存设计", agent.getContext().getToolCollection().getCurrentTask());
        Mockito.verifyNoInteractions(llm);
    }

    @Test
    public void shouldPreserveEvaluationEvidenceBeforeClearingToolContext() {
        ExecutorAgent agent = newExecutorAgent();
        ToolCall toolCall = ToolCall.builder()
                .id("call-search-1")
                .type("function")
                .function(ToolCall.Function.builder()
                        .name("deep_search")
                        .arguments("{\"query\":\"Spring AI\"}")
                        .build())
                .build();
        agent.getMemory().addMessage(Message.fromToolCalls("需要联网查证", List.of(toolCall)));
        agent.getMemory().addMessage(Message.toolMessage("Spring AI official documentation", "call-search-1", null));
        agent.getMemory().addMessage(Message.assistantMessage("已完成联网查证并保留来源。", null));
        agent.setToolCalls(List.of());

        agent.act();

        Assert.assertTrue(agent.getMemory().getMessages().stream()
                .noneMatch(message -> message.getRole() == RoleType.TOOL));
        Assert.assertTrue(agent.getLastRunEvaluationMessages().stream()
                .anyMatch(message -> message.getRole() == RoleType.TOOL
                        && message.getContent().contains("official documentation")));
        Assert.assertTrue(agent.getLastRunEvaluationMessages().stream()
                .anyMatch(message -> message.getToolCalls() != null && !message.getToolCalls().isEmpty()));
    }

    @Test
    public void shouldRequireExplicitNetworkToolOnlyUntilFirstToolCall() {
        ExecutorAgent agent = newExecutorAgent();
        agent.getContext().setQuery("先规划，再联网查证 Qdrant 是否支持混合检索");
        agent.getContext().setTask("查阅 Qdrant 官方文档");

        Assert.assertEquals(ToolChoice.REQUIRED,
                ReflectionTestUtils.invokeMethod(agent, "resolveExecutorToolChoice"));

        agent.setExplicitToolRequirementSatisfied(true);
        agent.setCurrentRunExplicitToolRequirementSatisfied(true);
        Assert.assertEquals(ToolChoice.AUTO,
                ReflectionTestUtils.invokeMethod(agent, "resolveExecutorToolChoice"));

        agent.setCurrentRunExplicitToolRequirementSatisfied(false);
        agent.getContext().setTask("归纳两点结论");
        Assert.assertEquals(ToolChoice.AUTO,
                ReflectionTestUtils.invokeMethod(agent, "resolveExecutorToolChoice"));

        agent.getContext().setTask("查阅 Qdrant 官方文档最新版");
        Assert.assertEquals(ToolChoice.REQUIRED,
                ReflectionTestUtils.invokeMethod(agent, "resolveExecutorToolChoice"));

        agent.getContext().setQuery("不调用联网工具，直接基于已有信息回答");
        Assert.assertEquals(ToolChoice.AUTO,
                ReflectionTestUtils.invokeMethod(agent, "resolveExecutorToolChoice"));
    }

    @Test
    public void shouldRetryTextualToolIntentAsStructuredFunctionCall() {
        ExecutorAgent agent = newExecutorAgent();
        agent.getContext().getToolCollection().addTool(new StubSearchTool());
        agent.getMemory().addMessage(Message.userMessage("核验 CLI 功能", null));

        ToolCall structuredCall = ToolCall.builder()
                .id("call-search-retry")
                .type("function")
                .function(ToolCall.Function.builder()
                        .name("deep_search")
                        .arguments("{\"query\":\"todo CLI\"}")
                        .build())
                .build();
        LLM llm = Mockito.mock(LLM.class);
        Mockito.when(llm.askTool(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.anyInt()))
                .thenReturn(CompletableFuture.completedFuture(LLM.ToolCallResponse.builder()
                                .content("思考：需要查证。\n行动：deep_search[todo CLI]\n观察：")
                                .toolCalls(List.of())
                                .build()),
                        CompletableFuture.completedFuture(LLM.ToolCallResponse.builder()
                                .content("正在查证")
                                .toolCalls(List.of(structuredCall))
                                .build()));
        agent.setLlm(llm);

        Assert.assertTrue(agent.think());

        Assert.assertEquals(List.of(structuredCall), agent.getToolCalls());
        ArgumentCaptor<ToolChoice> choices = ArgumentCaptor.forClass(ToolChoice.class);
        Mockito.verify(llm, Mockito.times(2)).askTool(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(),
                choices.capture(), Mockito.any(), Mockito.anyBoolean(), Mockito.anyInt());
        Assert.assertEquals(List.of(ToolChoice.AUTO, ToolChoice.AUTO), choices.getAllValues());
    }

    private ExecutorAgent newExecutorAgent() {
        ReactorConfig config = new ReactorConfig();
        config.setExecutorSystemPromptMap("{}");
        config.setExecutorNextStepPromptMap("{}");
        config.setExecutorSopPromptMap("{}");
        ReflectionTestUtils.setField(config, "executorModelName", "test-executor-model");
        ReflectionTestUtils.setField(config, "executorMaxSteps", 10);
        ReflectionTestUtils.setField(config, "maxObserve", "2048");
        ReflectionTestUtils.setField(config, "taskPrePrompt", "");
        ReflectionTestUtils.setField(config, "clearToolMessage", "1");
        ReflectionTestUtils.setField(config, "taskCompleteDesc", "当前 task 完成，请推进计划");
        ReflectionTestUtils.setField(config, "digitalEmployeePrompt", "");
        ReflectionTestUtils.setField(config, "llmSettingsMap", Map.of(
                "test-executor-model",
                LLMSettings.builder()
                        .model("test-executor-model")
                        .maxTokens(1024)
                        .temperature(0)
                        .baseUrl("http://127.0.0.1")
                        .interfaceUrl("/v1/chat/completions")
                        .functionCallType("function_call")
                        .apiKey("test-key")
                        .maxInputTokens(4096)
                        .build()
        ));
        ReactorRuntimeDependencies runtimeDependencies = ReactorRuntimeTestSupport.runtimeDependencies(config);

        ToolCollection tools = new ToolCollection();
        AgentContext context = AgentContext.builder()
                .requestId("req-executor-result-001")
                .sessionId("session-executor-result-001")
                .query("设计缓存")
                .dateInfo("2026-07-13")
                .basePrompt("")
                .sopPrompt("")
                .historyDialogue("")
                .printer(Mockito.mock(Printer.class))
                .toolCollection(tools)
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(runtimeDependencies)
                .build();
        tools.setAgentContext(context);
        return new ExecutorAgent(context);
    }

    private static final class StubSearchTool implements BaseTool {

        @Override
        public String getName() {
            return "deep_search";
        }

        @Override
        public String getDescription() {
            return "search";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of();
        }

        @Override
        public Object execute(Object input) {
            return "ok";
        }
    }
}
