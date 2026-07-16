package org.wwz.ai.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.llm.LLM;
import org.wwz.ai.domain.agent.runtime.llm.LlmChatResponseMapper;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        Assert.assertTrue(printer.messages.stream().anyMatch(message -> "先分析".equals(message.message) && message.isFinal));
        Assert.assertFalse(printer.messages.stream().anyMatch(message -> String.valueOf(message.message).contains("function_name")));
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
        Assert.assertTrue(printer.messages.stream().anyMatch(message -> "先思考".equals(message.message) && message.isFinal));
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

        var future = handler.handleStringStreamResponse(
                        AgentContext.builder().requestId("req-timeout").isStream(false).build(),
                        Flux.concat(Flux.just(textChunk("超时前已输出")), Flux.never()),
                        null, false, false, partial::set)
                .orTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS);

        try {
            future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            Assert.fail("stream should time out");
        } catch (java.util.concurrent.ExecutionException expected) {
            Assert.assertTrue(expected.getCause() instanceof java.util.concurrent.TimeoutException);
            Assert.assertEquals("超时前已输出", partial.get());
        }
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
                .streamMessageType("plan_thought")
                .printer(printer)
                .build();

        LLM.ToolCallResponse response = handler.handleToolCallStream(
                context,
                Flux.just(toolChunk("先规划", new AssistantMessage.ToolCall("call-2", "function", "planning", "{\"command\":\"create\"}"), "tool_calls", 18)),
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

    private ChatResponse toolChunk(String content,
                                   AssistantMessage.ToolCall toolCall,
                                   String finishReason,
                                   Integer totalTokens) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content)
                .properties(java.util.Map.of())
                .toolCalls(List.of(toolCall))
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
        private final List<PrinterMessage> messages = new ArrayList<>();

        @Override
        public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
            messages.add(new PrinterMessage(messageId, messageType, message, isFinal));
        }

        @Override
        public void send(String messageId, String messageType, Object message, Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
            messages.add(new PrinterMessage(messageId, messageType, message, isFinal));
        }

        @Override
        public void send(String messageType, Object message) {
            messages.add(new PrinterMessage(null, messageType, message, true));
        }

        @Override
        public void send(String messageType, Object message, String digitalEmployee) {
            messages.add(new PrinterMessage(null, messageType, message, true));
        }

        @Override
        public void send(String messageId, String messageType, Object message, Boolean isFinal) {
            messages.add(new PrinterMessage(messageId, messageType, message, isFinal));
        }

        @Override
        public void sendWithResultMap(String messageId, String messageType, Object message, Map<String, Object> extraResultMap, Boolean isFinal) {
            messages.add(new PrinterMessage(messageId, messageType, message, isFinal));
        }

        @Override
        public void sendWithResultMap(String messageType, Object message, Map<String, Object> extraResultMap) {
            messages.add(new PrinterMessage(null, messageType, message, true));
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(AgentType agentType) {
        }
    }

    private record PrinterMessage(String messageId, String messageType, Object message, Boolean isFinal) {
    }
}
