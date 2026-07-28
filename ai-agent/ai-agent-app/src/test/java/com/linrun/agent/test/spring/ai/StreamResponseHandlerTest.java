package com.linrun.agent.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.llm.LLM;
import com.linrun.agent.domain.agent.runtime.llm.LlmChatResponseMapper;
import com.linrun.agent.domain.agent.runtime.llm.StreamResponseHandler;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * StreamResponseHandler 测试
 */
public class StreamResponseHandlerTest {

    @Test
    public void test_handleStringStreamStopsForwardingAfterStructParseMarker() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());

        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .isStream(true)
                .streamMessageType("tool_thought")
                .printer(printer)
                .build();

        String fullContent = handler.handleStringStream(
                context,
                Flux.just(
                        textChunk("先分析"),
                        textChunk("```"),
                        textChunk("json {\"function_name\":\"deep_search\"}```")
                ),
                "```json",
                true
        ).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Assert.assertEquals("先分析```json {\"function_name\":\"deep_search\"}```", fullContent);
        Assert.assertEquals("先分析", printer.text());
        Assert.assertFalse(printer.text().contains("function_name"));
    }

    @Test
    public void test_handleStringStreamFlushesIncompleteMarkerAtEnd() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());

        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = AgentContext.builder()
                .requestId("req-partial-marker")
                .isStream(true)
                .streamMessageType("tool_thought")
                .printer(printer)
                .build();

        handler.handleStringStream(context, Flux.just(textChunk("正文```")), "```json", true)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        Assert.assertEquals("正文```", printer.text());
    }

    @Test
    public void test_handleToolCallStreamAggregatesArgumentsAndFinalContent() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());

        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = AgentContext.builder()
                .requestId("req-2")
                .isStream(true)
                .streamMessageType("tool_thought")
                .printer(printer)
                .build();

        LLM.ToolCallResponse response = handler.handleToolCallStream(
                context,
                Flux.just(
                        toolChunk("先思考", new AssistantMessage.ToolCall("call-1", "function", "deep_search", "{\"query\":"), null, null),
                        toolChunk("", new AssistantMessage.ToolCall("call-1", "function", "deep_search", "\"spring ai\"}"), "tool_calls", 28)
                ),
                System.currentTimeMillis() - 10
        ).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Assert.assertEquals("先思考", response.getContent());
        Assert.assertEquals("tool_calls", response.getFinishReason());
        Assert.assertEquals(Integer.valueOf(28), response.getTotalTokens());
        Assert.assertEquals(Integer.valueOf(10), response.getPromptTokens());
        Assert.assertEquals(Integer.valueOf(18), response.getCompletionTokens());
        Assert.assertEquals(1, response.getToolCalls().size());
        Assert.assertEquals("{\"query\":\"spring ai\"}", response.getToolCalls().get(0).getFunction().getArguments());
        Assert.assertEquals("先思考", printer.text());
    }

    @Test
    public void test_handleToolCallStreamGeneratesStableUniqueIdsWhenProviderOmitsThem() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());

        AgentContext context = AgentContext.builder()
                .requestId("req-missing-tool-call-id")
                .isStream(false)
                .build();
        LLM.ToolCallResponse response = handler.handleToolCallStream(
                context,
                Flux.just(
                        toolChunk("", List.of(
                                new AssistantMessage.ToolCall(null, "function", "deep_search", "{\"query\":"),
                                new AssistantMessage.ToolCall("", "function", "web_fetch", "{\"url\":")
                        ), null, null),
                        toolChunk("", List.of(
                                new AssistantMessage.ToolCall(null, "function", "deep_search", "\"spring ai\"}"),
                                new AssistantMessage.ToolCall("", "function", "web_fetch", "\"https://example.com\"}")
                        ), "tool_calls", 24)
                ),
                System.currentTimeMillis() - 10
        ).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Assert.assertEquals(2, response.getToolCalls().size());
        String firstId = response.getToolCalls().get(0).getId();
        String secondId = response.getToolCalls().get(1).getId();
        Assert.assertFalse(firstId.isBlank());
        Assert.assertFalse(secondId.isBlank());
        Assert.assertNotEquals(firstId, secondId);
        Assert.assertEquals("{\"query\":\"spring ai\"}",
                response.getToolCalls().get(0).getFunction().getArguments());
        Assert.assertEquals("{\"url\":\"https://example.com\"}",
                response.getToolCalls().get(1).getFunction().getArguments());
    }

    @Test
    public void test_partialObserverKeepsOutputWhenStreamFails() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());
        AtomicReference<String> partial = new AtomicReference<>("");

        var future = handler.handleStringStreamResponse(
                AgentContext.builder().requestId("req-partial").isStream(false).build(),
                Flux.concat(Flux.just(textChunk("已经输出")), Flux.error(new RuntimeException("broken"))),
                null, false, false, partial::set);

        try {
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.fail("stream should fail");
        } catch (java.util.concurrent.ExecutionException expected) {
            Assert.assertEquals("已经输出", partial.get());
        }
    }

    @Test
    public void test_partialObserverKeepsOutputWhenCallerTimesOut() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());
        AtomicReference<String> partial = new AtomicReference<>("");
        CountDownLatch subscriptionCancelled = new CountDownLatch(1);

        var future = handler.handleStringStreamResponse(
                        AgentContext.builder().requestId("req-timeout").isStream(false).build(),
                        Flux.concat(Flux.just(textChunk("超时前已输出")), Flux.never())
                                .doOnCancel(subscriptionCancelled::countDown),
                        null, false, false, partial::set)
                .orTimeout(200, TimeUnit.MILLISECONDS);

        try {
            future.get(2, TimeUnit.SECONDS);
            Assert.fail("stream should time out");
        } catch (java.util.concurrent.ExecutionException expected) {
            Assert.assertTrue(expected.getCause() instanceof java.util.concurrent.TimeoutException);
            Assert.assertEquals("超时前已输出", partial.get());
            // CompletableFuture 先发布超时终态，再同步触发 whenComplete 中的 subscription.dispose()；
            // 等待明确的 Reactor cancel 信号，避免断言与清理回调之间的调度竞态。
            Assert.assertTrue(
                    "timeout must cancel the provider stream",
                    subscriptionCancelled.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    public void test_handleStringStreamCancellationDisposesProviderSubscription() {
        StreamResponseHandler handler = createHandler();
        AtomicBoolean subscriptionCancelled = new AtomicBoolean();

        var future = handler.handleStringStream(
                AgentContext.builder().requestId("req-cancel-text").isStream(false).build(),
                Flux.<ChatResponse>never().doOnCancel(() -> subscriptionCancelled.set(true)));

        Assert.assertTrue(future.cancel(true));
        Assert.assertTrue(future.isCancelled());
        Assert.assertTrue("cancel must dispose the provider stream", subscriptionCancelled.get());
    }

    @Test
    public void test_handleToolCallStreamCancellationDisposesProviderSubscription() {
        StreamResponseHandler handler = createHandler();
        AtomicBoolean subscriptionCancelled = new AtomicBoolean();

        var future = handler.handleToolCallStream(
                AgentContext.builder().requestId("req-cancel-tool").isStream(false).build(),
                Flux.<ChatResponse>never().doOnCancel(() -> subscriptionCancelled.set(true)),
                System.currentTimeMillis(),
                false);

        Assert.assertTrue(future.cancel(true));
        Assert.assertTrue(future.isCancelled());
        Assert.assertTrue("cancel must dispose the provider stream", subscriptionCancelled.get());
    }

    @Test
    public void test_handleToolCallStreamKeepsMessageIdWhenForwardingIsDisabled() throws Exception {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());

        RecordingPrinter printer = new RecordingPrinter();
        AgentContext context = AgentContext.builder()
                .requestId("req-3")
                .isStream(true)
                .streamMessageType("agent_stream")
                .printer(printer)
                .build();

        LLM.ToolCallResponse response = handler.handleToolCallStream(
                context,
                Flux.just(toolChunk("更新待办", new AssistantMessage.ToolCall("call-2", "function", "todo_write", "{\"command\":\"create\"}"), "tool_calls", 18)),
                System.currentTimeMillis() - 10,
                false
        ).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Assert.assertNotNull(response.getStreamMessageId());
        Assert.assertTrue(printer.messages.isEmpty());
    }

    private ChatResponse textChunk(String content) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content)
                .properties(java.util.Map.of())
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    private StreamResponseHandler createHandler() {
        StreamResponseHandler handler = new StreamResponseHandler();
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMessageInterval("{\"llm\":\"1,2\"}");
        ReflectionTestUtils.setField(handler, "reactorConfig", reactorConfig);
        ReflectionTestUtils.setField(handler, "chatResponseMapper", new LlmChatResponseMapper());
        return handler;
    }

    private ChatResponse toolChunk(String content,
                                   AssistantMessage.ToolCall toolCall,
                                   String finishReason,
                                   Integer totalTokens) {
        return toolChunk(content, List.of(toolCall), finishReason, totalTokens);
    }

    private ChatResponse toolChunk(String content,
                                   List<AssistantMessage.ToolCall> toolCalls,
                                   String finishReason,
                                   Integer totalTokens) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content)
                .properties(java.util.Map.of())
                .toolCalls(toolCalls)
                .build();
        if (finishReason == null && totalTokens == null) {
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        if (totalTokens == null) {
            return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)));
        }
        ChatResponseMetadata responseMetadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, totalTokens - 10, totalTokens))
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)), responseMetadata);
    }

    private static class RecordingPrinter implements Printer {
        private final List<AgentStreamEvent> messages = new ArrayList<>();

        @Override
        public void send(AgentStreamEvent event) {
            messages.add(event);
        }

        @Override
        public void close() {
        }

        private String text() {
            StringBuilder result = new StringBuilder();
            for (AgentStreamEvent event : messages) {
                if (event instanceof AgentStreamEvent.Text text) {
                    result.append(text.delta());
                }
            }
            return result.toString();
        }
    }
}
