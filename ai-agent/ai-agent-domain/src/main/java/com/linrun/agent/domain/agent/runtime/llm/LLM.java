package com.linrun.agent.domain.agent.runtime.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.CollectionUtils;
import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.quota.QuotaProviderAlreadyStartedException;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ExplicitToolChoicePolicy;
import com.linrun.agent.domain.agent.runtime.context.ContextBudget;
import com.linrun.agent.domain.agent.runtime.context.ContextCompactor;
import com.linrun.agent.domain.agent.runtime.context.ContextManager;
import com.linrun.agent.domain.agent.runtime.context.ManagedContext;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;
import com.linrun.agent.domain.agent.runtime.executor.AgentExecutorSupport;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.util.StringUtil;
import com.linrun.agent.domain.agent.runtime.util.ToolSchemaNormalizer;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import com.linrun.agent.domain.agent.runtime.ReactorLlmDependencies;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM主体类。
 */
@Slf4j
@Data
public class LLM {

    private static final Map<String, LLM> instances = new ConcurrentHashMap<>();
    private static final String STRUCT_PARSE = "struct_parse";
    private static final String FUNCTION = "function";
    private static final String STRUCT_PARSE_JSON_MARKER = "```json";
    private static final Pattern STRUCT_PARSE_JSON_PATTERN = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");

    /** 模型标识。 */
    private final String model;
    /** LLM ERP 标识。 */
    private final String llmErp;
    /** 最大输出 token。 */
    private final int maxTokens;
    /** 默认温度。 */
    private final double temperature;
    /** API Key。 */
    private final String apiKey;
    /** Base URL。 */
    private final String baseUrl;
    /** 接口路径。 */
    private final String interfaceUrl;
    /** 工具调用模式。 */
    private final String functionCallType;
    /** token 计数器，保留给截断逻辑使用。 */
    private final TokenCounter tokenCounter;
    /** 所有主 LLM 路径共享的上下文预算与信任边界。 */
    private final ContextCompactor contextManager;
    /** JSON 工具。 */
    private final ObjectMapper objectMapper;
    /** 扩展参数。 */
    private final Map<String, Object> extParams;
    /** 原始模型配置，供 Spring AI 解析器复用。 */
    private final LLMSettings llmSettings;

    /** 累计输入 token。 */
    private int totalInputTokens;
    /** 最大输入 token。 */
    private Integer maxInputTokens;

    /** 显式注入的运行时依赖。 */
    private final transient ReactorRuntimeDependencies runtimeDependencies;
    private final transient LlmChatModelResolver chatModelResolver;
    private final transient OpenAiChatOptionsFactory chatOptionsFactory;
    private final transient DomainMessageConverter messageConverter;
    private final transient LlmChatResponseMapper responseMapper;
    private final transient StreamResponseHandler streamResponseHandler;

    public LLM(String modelName, String llmErp, ReactorRuntimeDependencies runtimeDependencies) {
        this(requireRuntimeDependencies(runtimeDependencies).resolveLlmSettings(modelName), llmErp, runtimeDependencies);
    }

    /**
     * 直接以已解析的 {@link LLMSettings} 构造 LLM。
     * 用于用户请求级模型覆盖：上层先按 modelId 解析出 settings，再据此实例化，
     * 避免把 modelId/modelName 的解析歧义带进构造器。
     */
    public LLM(LLMSettings config, String llmErp, ReactorRuntimeDependencies runtimeDependencies) {
        this.llmErp = llmErp;
        this.runtimeDependencies = requireRuntimeDependencies(runtimeDependencies);
        ReactorLlmDependencies llmDependencies = this.runtimeDependencies.requireLlmDependencies();
        this.chatModelResolver = llmDependencies.getChatModelResolver();
        this.chatOptionsFactory = llmDependencies.getChatOptionsFactory();
        this.messageConverter = llmDependencies.getMessageConverter();
        this.responseMapper = llmDependencies.getResponseMapper();
        this.streamResponseHandler = llmDependencies.getStreamResponseHandler();

        if (config == null) {
            throw new IllegalArgumentException("LLMSettings must not be null");
        }
        this.llmSettings = config;
        this.model = config.getModel();
        this.maxTokens = config.getMaxTokens();
        this.temperature = config.getTemperature();
        this.apiKey = config.getApiKey();

        String baseUrlFromConfig = config.getBaseUrl();
        if (StringUtils.isBlank(baseUrlFromConfig)) {
            throw new IllegalArgumentException(
                    "Base URL is not configured or empty. Please set llm.default.base_url in application.yml, or configure llm.settings for model: "
                            + config.getModel());
        }
        this.baseUrl = baseUrlFromConfig;
        this.interfaceUrl = StringUtils.isNotBlank(config.getInterfaceUrl())
                ? config.getInterfaceUrl()
                : "/v1/chat/completions";
        this.functionCallType = config.getFunctionCallType();
        this.totalInputTokens = 0;
        this.maxInputTokens = config.getMaxInputTokens();
        this.extParams = config.getExtParams();
        this.tokenCounter = new TokenCounter();
        this.contextManager = new ContextManager(this.tokenCounter);
        this.objectMapper = new ObjectMapper();
    }

    /** 将领域消息转换为稳定的 token 估算结构。 */
    public List<Map<String, Object>> formatMessages(List<Message> messages, boolean isClaude) {
        List<Map<String, Object>> formattedMessages = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return formattedMessages;
        }

        for (Message message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            Map<String, Object> messageMap = new HashMap<>();
            if (StringUtils.isNotBlank(message.getBase64Image())) {
                List<Map<String, Object>> multimodalContent = new ArrayList<>();
                Map<String, String> imageUrlMap = new HashMap<>();
                imageUrlMap.put("url", "data:image/jpeg;base64," + message.getBase64Image());
                Map<String, Object> imageMap = new HashMap<>();
                imageMap.put("type", "image_url");
                imageMap.put("image_url", imageUrlMap);
                multimodalContent.add(imageMap);

                Map<String, Object> textMap = new HashMap<>();
                textMap.put("type", "text");
                textMap.put("text", message.getContent());
                multimodalContent.add(textMap);

                messageMap.put("role", message.getRole().getValue());
                messageMap.put("content", multimodalContent);
            } else if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                if (isClaude) {
                    messageMap.put("role", message.getRole().getValue());
                    List<Map<String, Object>> claudeToolCalls = new ArrayList<>();
                    for (ToolCall toolCall : message.getToolCalls()) {
                        if (toolCall == null || toolCall.getFunction() == null) {
                            continue;
                        }
                        Map<String, Object> claudeToolCall = new HashMap<>();
                        claudeToolCall.put("type", "tool_use");
                        claudeToolCall.put("id", toolCall.getId());
                        claudeToolCall.put("name", toolCall.getFunction().getName());
                        claudeToolCall.put("input", JSON.parseObject(toolCall.getFunction().getArguments()));
                        claudeToolCalls.add(claudeToolCall);
                    }
                    messageMap.put("content", claudeToolCalls);
                } else {
                    messageMap.put("role", message.getRole().getValue());
                    List<Map<String, Object>> toolCallsMap = JSON.parseObject(
                            JSON.toJSONString(message.getToolCalls()),
                            new TypeReference<List<Map<String, Object>>>() {
                            });
                    messageMap.put("tool_calls", toolCallsMap);
                }
            } else if (StringUtils.isNotBlank(message.getToolCallId())) {
                ReactorConfig reactorConfig = runtimeDependencies.requireReactorConfig();
                String content = StringUtil.textDesensitization(message.getContent(), reactorConfig.getSensitivePatterns());
                if (isClaude) {
                    messageMap.put("role", "user");
                    List<Map<String, Object>> claudeToolCalls = new ArrayList<>();
                    Map<String, Object> claudeToolCall = new HashMap<>();
                    claudeToolCall.put("type", "tool_result");
                    claudeToolCall.put("tool_use_id", message.getToolCallId());
                    claudeToolCall.put("content", content);
                    claudeToolCalls.add(claudeToolCall);
                    messageMap.put("content", claudeToolCalls);
                } else {
                    messageMap.put("role", message.getRole().getValue());
                    messageMap.put("content", content);
                    messageMap.put("tool_call_id", message.getToolCallId());
                }
            } else {
                messageMap.put("role", message.getRole().getValue());
                messageMap.put("content", message.getContent());
            }
            formattedMessages.add(messageMap);
        }
        return formattedMessages;
    }

    /**
     * 纯文本问答统一走 Spring AI。
     */
    public CompletableFuture<String> ask(
            AgentContext context,
            List<Message> messages,
            List<Message> systemMsgs,
            boolean stream,
            Double temperature
    ) {
        return ask(
                context,
                messages,
                systemMsgs,
                stream,
                true,
                temperature,
                ExecutionLedgerConstants.CALL_KIND_ASK
        );
    }

    /**
     * 纯文本问答统一走 Spring AI，并允许显式控制是否向前端分发流式增量。
     */
    public CompletableFuture<String> ask(
            AgentContext context,
            List<Message> messages,
            List<Message> systemMsgs,
            boolean stream,
            boolean pushToClient,
            Double temperature
    ) {
        return ask(
                context,
                messages,
                systemMsgs,
                stream,
                pushToClient,
                temperature,
                ExecutionLedgerConstants.CALL_KIND_ASK
        );
    }

    /**
     * 纯文本问答统一走 Spring AI，并允许调用方覆盖账本语义。
     * 用于内部 ask 与面向用户的 ask 共享执行链，但在回放时做语义隔离。
     */
    public CompletableFuture<String> ask(
            AgentContext context,
            List<Message> messages,
            List<Message> systemMsgs,
            boolean stream,
            boolean pushToClient,
            Double temperature,
            String callKind
    ) {
        try {
            List<Message> requestMessages = prepareRequestMessages(
                    context, mergeMessages(systemMsgs, messages), null);
            LlmInvocationHandle invocationHandle = startLlmInvocation(
                    context,
                    callKind,
                    stream
            );
            reserveLlmCall(context, invocationHandle, requestMessages, null);
            Prompt prompt = buildPrompt(
                    requestMessages,
                    chatOptionsFactory.buildTextOptions(llmSettings, temperature, invocationHandle.maxOutputTokens())
            );
            OpenAiChatModel chatModel = resolveChatModel();
            markLlmProviderStarted(context, invocationHandle);

            log.info("{} call llm ask via Spring AI, model={}, stream={}",
                    context.getRequestId(), model, stream);

            if (!stream) {
                return AgentExecutorSupport.supplyAsync(runtimeDependencies.requireLlmExecutor(), "llmAsk", () -> {
                    try {
                        ChatResponse response = chatModel.call(prompt);
                        observeProviderResponse(invocationHandle, response);
                        String content = responseMapper.toText(response);
                        finishLlmInvocation(
                                context,
                                invocationHandle,
                                ExecutionLedgerConstants.STATUS_SUCCESS,
                                content,
                                0,
                                resolvePromptTokens(response.getMetadata()),
                                resolveCompletionTokens(response.getMetadata()),
                                resolveTotalTokens(response.getMetadata()),
                                resolveFinishReason(response),
                                null
                        );
                        return content;
                    } catch (Exception e) {
                        finishLlmInvocation(
                                context,
                                invocationHandle,
                                ExecutionLedgerConstants.resolveFailureStatus(e),
                                null,
                                0,
                                null,
                                null,
                                null,
                                null,
                                e.getMessage()
                        );
                        throw new CompletionException(e);
                    }
                });
            }

            CompletableFuture<StreamResponseHandler.StreamedTextResponse> streamFuture =
                    streamResponseHandler.handleStringStreamResponse(
                            context, chatModel.stream(prompt), null, false, pushToClient,
                            partial -> observePartialOutput(invocationHandle, partial));
            CompletableFuture<StreamResponseHandler.StreamedTextResponse> finalizedStreamFuture =
                    LlmCallFinalizer.finalizeCall(streamFuture, (response, throwable) -> {
                if (throwable == null) {
                    finishLlmInvocation(
                            context,
                            invocationHandle,
                            ExecutionLedgerConstants.STATUS_SUCCESS,
                            response.content(),
                            0,
                            response.promptTokens(),
                            response.completionTokens(),
                            response.totalTokens(),
                            null,
                            null
                    );
                    return;
                }
                finishLlmInvocation(
                        context,
                        invocationHandle,
                        ExecutionLedgerConstants.resolveFailureStatus(throwable),
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        throwable.getMessage()
                );
            });
            return mapWithCancellation(
                    finalizedStreamFuture, StreamResponseHandler.StreamedTextResponse::content);
        } catch (Exception e) {
            log.error("{} Unexpected error in ask: {}", context.getRequestId(), e.getMessage(), e);
            return failedFuture(e);
        }
    }

    /**
     * 工具调用统一门面。
     * function_call 走 Spring AI 原生工具调用；struct_parse 保留兼容分支。
     */
    public CompletableFuture<ToolCallResponse> askTool(
            AgentContext context,
            List<Message> messages,
            Message systemMsgs,
            ToolCollection tools,
            ToolChoice toolChoice,
            Double temperature,
            boolean stream,
            int timeout
    ) {
        return askTool(context, messages, systemMsgs, tools, toolChoice, temperature, stream, true, timeout);
    }

    /**
     * 工具调用统一门面，并允许调用方控制是否向前端透传流式增量。
     */
    public CompletableFuture<ToolCallResponse> askTool(
            AgentContext context,
            List<Message> messages,
            Message systemMsgs,
            ToolCollection tools,
            ToolChoice toolChoice,
            Double temperature,
            boolean stream,
            boolean pushToClient,
            int timeout
    ) {
        try {
            if (!ToolChoice.isValid(toolChoice)) {
                throw new IllegalArgumentException("Invalid tool_choice: " + toolChoice);
            }
            String requiredToolName = resolveRequiredToolName(context, tools, toolChoice);

            LlmInvocationHandle invocationHandle = startLlmInvocation(
                    context,
                    ExecutionLedgerConstants.CALL_KIND_ASK_TOOL,
                    stream
            );
            long startTime = System.currentTimeMillis();
            if (isStructParseMode()) {
                return askToolWithStructParse(
                        context, messages, systemMsgs, tools, temperature, stream, timeout, startTime, invocationHandle);
            }

            List<Message> requestMessages = prepareRequestMessages(
                    context, mergeMessages(systemMsgs, messages), tools);
            reserveLlmCall(context, invocationHandle, requestMessages, tools);
            Prompt prompt = buildPrompt(
                    requestMessages,
                    chatOptionsFactory.buildToolOptions(
                            llmSettings, temperature, tools, toolChoice,
                            requiredToolName, invocationHandle.maxOutputTokens())
            );
            OpenAiChatModel chatModel = resolveChatModel();
            markLlmProviderStarted(context, invocationHandle);

            log.info("{} call llm askTool via Spring AI, model={}, stream={}, mode=function_call",
                    context.getRequestId(), model, stream);

            if (!stream) {
                CompletableFuture<ToolCallResponse> source = AgentExecutorSupport.supplyAsync(
                        runtimeDependencies.requireLlmExecutor(),
                        "llmAskToolFunctionCall",
                        () -> {
                    try {
                        ChatResponse response = chatModel.call(prompt);
                        observeProviderResponse(invocationHandle, response);
                        return responseMapper.toToolCallResponse(response, startTime);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }).orTimeout(timeout, TimeUnit.SECONDS);

                return LlmCallFinalizer.finalizeCall(source, (response, throwable) -> {
                    if (throwable == null) {
                        finishLlmInvocation(context, invocationHandle, response, null);
                        return;
                    }
                    finishLlmInvocation(context, invocationHandle, null, throwable);
                });
            }

            CompletableFuture<ToolCallResponse> streamFuture = streamResponseHandler.handleToolCallStream(
                    context, chatModel.stream(prompt), startTime, pushToClient,
                    partial -> observePartialOutput(invocationHandle, partial));
            streamFuture.orTimeout(timeout, TimeUnit.SECONDS);
            return LlmCallFinalizer.finalizeCall(streamFuture, (response, throwable) -> {
                if (throwable == null) {
                    finishLlmInvocation(context, invocationHandle, response, null);
                    return;
                }
                finishLlmInvocation(context, invocationHandle, null, throwable);
            });
        } catch (Exception e) {
            log.error("{} Unexpected error in askTool: {}", context.getRequestId(), e.getMessage(), e);
            return failedFuture(e);
        }
    }

    /**
     * struct_parse 兼容路径。
     * 仍然让模型输出文本中的 JSON 代码块，但底层同样走 Spring AI 的文本 call/stream。
     */
    private CompletableFuture<ToolCallResponse> askToolWithStructParse(
            AgentContext context,
            List<Message> messages,
            Message systemMsg,
            ToolCollection tools,
            Double temperature,
            boolean stream,
            int timeout,
            long startTime,
            LlmInvocationHandle invocationHandle
    ) {
        Message mergedSystemMessage = buildStructParseSystemMessage(systemMsg, tools);
        List<Message> requestMessages = prepareRequestMessages(
                context, mergeMessages(mergedSystemMessage, messages), null);
        reserveLlmCall(context, invocationHandle, requestMessages, null);
        Prompt prompt = buildPrompt(
                requestMessages,
                chatOptionsFactory.buildTextOptions(llmSettings, temperature, invocationHandle.maxOutputTokens())
        );
        OpenAiChatModel chatModel = resolveChatModel();
        markLlmProviderStarted(context, invocationHandle);

        log.info("{} call llm askTool via Spring AI, model={}, stream={}, mode=struct_parse",
                context.getRequestId(), model, stream);

        if (!stream) {
            CompletableFuture<ToolCallResponse> source = AgentExecutorSupport.supplyAsync(
                    runtimeDependencies.requireLlmExecutor(), "llmAskToolStructParse", () -> {
                try {
                    ChatResponse response = chatModel.call(prompt);
                    observeProviderResponse(invocationHandle, response);
                    ToolCallResponse toolCallResponse = buildStructParseToolCallResponse(
                            context,
                            responseMapper.toText(response),
                            resolveFinishReason(response),
                            resolveTotalTokens(response.getMetadata()),
                            startTime
                    );
                    toolCallResponse.setPromptTokens(resolvePromptTokens(response.getMetadata()));
                    toolCallResponse.setCompletionTokens(resolveCompletionTokens(response.getMetadata()));
                    return toolCallResponse;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).orTimeout(timeout, TimeUnit.SECONDS);
            return LlmCallFinalizer.finalizeCall(source, (response, throwable) -> {
                if (throwable == null) {
                    finishLlmInvocation(context, invocationHandle, response, null);
                } else {
                    finishLlmInvocation(context, invocationHandle, null, throwable);
                }
            });
        }

        CompletableFuture<StreamResponseHandler.StreamedTextResponse> streamFuture =
                streamResponseHandler.handleStringStreamResponse(
                        context,
                        chatModel.stream(prompt),
                        STRUCT_PARSE_JSON_MARKER,
                        true,
                        true,
                        partial -> observePartialOutput(invocationHandle, partial)
                );
        streamFuture.orTimeout(timeout, TimeUnit.SECONDS);
        CompletableFuture<ToolCallResponse> result = mapWithCancellation(streamFuture, streamed -> {
            ToolCallResponse response = buildStructParseToolCallResponse(
                    context, streamed.content(), null, streamed.totalTokens(), startTime);
            response.setPromptTokens(streamed.promptTokens());
            response.setCompletionTokens(streamed.completionTokens());
            return response;
        });
        return LlmCallFinalizer.finalizeCall(result, (response, throwable) -> {
            if (throwable == null) {
                finishLlmInvocation(context, invocationHandle, response, null);
                return;
            }
            finishLlmInvocation(context, invocationHandle, null, throwable);
        });
    }

    private String resolveRequiredToolName(AgentContext context,
                                           ToolCollection tools,
                                           ToolChoice toolChoice) {
        if (toolChoice != ToolChoice.REQUIRED || context == null || tools == null) {
            return null;
        }
        Set<String> availableToolNames = new LinkedHashSet<>();
        if (tools.getToolMap() != null) {
            availableToolNames.addAll(tools.getToolMap().keySet());
        }
        if (tools.getMcpToolMap() != null) {
            availableToolNames.addAll(tools.getMcpToolMap().keySet());
        }
        return ExplicitToolChoicePolicy.resolveRequiredToolNameForCurrentTask(
                context.getQuery(), context.getTask(), 1, availableToolNames);
    }

    /**
     * 将 struct_parse 的文本响应映射回既有 ToolCallResponse。
     */
    private ToolCallResponse buildStructParseToolCallResponse(
            AgentContext context,
            String fullContent,
            String finishReason,
            Integer totalTokens,
            long startTime
    ) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (String match : findMatches(fullContent, STRUCT_PARSE_JSON_PATTERN)) {
            ToolCall toolCall = parseToolCall(context, match);
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }

        String visibleContent = extractVisibleContent(fullContent);
        visibleContent = StringUtils.trimToNull(visibleContent);
        if (visibleContent == null && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Empty or invalid response from LLM");
        }

        return ToolCallResponse.builder()
                .content(visibleContent)
                .toolCalls(toolCalls)
                .finishReason(finishReason)
                .totalTokens(totalTokens)
                .duration(System.currentTimeMillis() - startTime)
                .build();
    }

    private String extractVisibleContent(String fullContent) {
        if (fullContent == null) {
            return null;
        }
        int stopPos = fullContent.indexOf(STRUCT_PARSE_JSON_MARKER);
        return stopPos >= 0 ? fullContent.substring(0, stopPos) : fullContent;
    }

    private Message buildStructParseSystemMessage(Message systemMsg, ToolCollection tools) {
        String toolPrompt = buildStructParseToolPrompt(tools);
        String originalSystemPrompt = systemMsg != null ? StringUtils.defaultString(systemMsg.getContent()) : "";
        String mergedContent = StringUtils.isBlank(originalSystemPrompt)
                ? toolPrompt
                : originalSystemPrompt + "\n" + toolPrompt;
        return Message.systemMessage(mergedContent, null);
    }

    /**
     * struct_parse 模式仍复用原来的工具描述文本，但不再关心 GPT/Claude 的手工分支。
     */
    private String buildStructParseToolPrompt(ToolCollection tools) {
        ReactorConfig reactorConfig = runtimeDependencies.requireReactorConfig();
        StringBuilder prompt = new StringBuilder(StringUtils.defaultString(reactorConfig.getStructParseToolSystemPrompt()));
        if (prompt.length() > 0) {
            prompt.append('\n');
        }

        if (tools == null) {
            return prompt.toString();
        }

        for (BaseTool tool : tools.getToolMap().values()) {
            Map<String, Object> functionMap = new LinkedHashMap<>();
            functionMap.put("name", tool.getName());
            functionMap.put("description", tool.getDescription());
            functionMap.put("parameters",
                    addFunctionNameParam(normalizeToolParameters(tool.toParams(), tool.getName()), tool.getName()));
            prompt.append(String.format("- `%s`%n```json %s ```%n", tool.getName(), JSON.toJSONString(functionMap)));
        }

        for (McpToolInfo tool : tools.getMcpToolMap().values()) {
            Map<String, Object> functionMap = new LinkedHashMap<>();
            functionMap.put("name", tool.resolveExposedName());
            functionMap.put("description", tool.getDesc());
            functionMap.put("parameters",
                    addFunctionNameParam(parseAndNormalizeToolParameters(tool.getParameters(), tool.resolveExposedName()), tool.resolveExposedName()));
            prompt.append(String.format("- `%s`%n```json %s ```%n", tool.resolveExposedName(), JSON.toJSONString(functionMap)));
        }
        return prompt.toString();
    }

    private Prompt buildPrompt(List<Message> domainMessages, OpenAiChatOptions options) {
        return new Prompt(messageConverter.convert(domainMessages), options);
    }

    private List<Message> prepareRequestMessages(AgentContext context,
                                                 List<Message> messages,
                                                 ToolCollection tools) {
        int toolSchemaTokens = estimateToolTokens(tools);
        ManagedContext managed = contextManager.prepare(
                messages,
                ContextBudget.forModel(maxInputTokens, toolSchemaTokens)
        );
        if (managed.compacted()) {
            log.info("{} context compacted: messageTokens {} -> {}, toolSchemaTokens={}, maxInputTokens={}",
                    context == null ? null : context.getRequestId(),
                    managed.originalMessageTokens(),
                    managed.finalMessageTokens(),
                    toolSchemaTokens,
                    maxInputTokens);
        }
        return managed.messages();
    }

    private Message firstSystemMessage(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        return messages.stream()
                .filter(message -> message != null && message.getRole() == RoleType.SYSTEM)
                .findFirst()
                .orElse(null);
    }

    private List<Message> withoutSystemMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> message != null && message.getRole() != RoleType.SYSTEM)
                .toList();
    }

    private List<Message> mergeMessages(List<Message> systemMsgs, List<Message> messages) {
        List<Message> mergedMessages = new ArrayList<>();
        if (systemMsgs != null) {
            for (Message systemMsg : systemMsgs) {
                if (systemMsg != null) {
                    mergedMessages.add(systemMsg);
                }
            }
        }
        if (messages != null) {
            for (Message message : messages) {
                if (message != null) {
                    mergedMessages.add(message);
                }
            }
        }
        return mergedMessages;
    }

    private List<Message> mergeMessages(Message systemMsg, List<Message> messages) {
        List<Message> mergedMessages = new ArrayList<>();
        if (systemMsg != null) {
            mergedMessages.add(systemMsg);
        }
        if (messages != null) {
            for (Message message : messages) {
                if (message != null) {
                    mergedMessages.add(message);
                }
            }
        }
        return mergedMessages;
    }

    private OpenAiChatModel resolveChatModel() {
        return chatModelResolver.resolve(llmSettings);
    }

    private boolean isStructParseMode() {
        return STRUCT_PARSE.equals(functionCallType);
    }

    private boolean isClaudeModel() {
        return model != null && model.contains("claude");
    }

    private String resolveFinishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    private Integer resolveTotalTokens(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        Usage usage = metadata.getUsage();
        return usage != null ? usage.getTotalTokens() : null;
    }

    private Integer resolvePromptTokens(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        Usage usage = metadata.getUsage();
        return usage != null ? usage.getPromptTokens() : null;
    }

    private Integer resolveCompletionTokens(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        Usage usage = metadata.getUsage();
        return usage != null ? usage.getCompletionTokens() : null;
    }

    private LlmInvocationHandle startLlmInvocation(AgentContext context, String callKind, boolean stream) {
        long inputRate = positiveRate(llmSettings.getInputCreditsPerMillion(), 5L);
        long outputRate = positiveRate(llmSettings.getOutputCreditsPerMillion(), 30L);
        if (context == null || !context.hasActiveLedgerRun() || context.getAgentRunState() == null) {
            if (context != null && context.getOwnerId() != null) {
                throw new IllegalStateException(
                        "authenticated LLM calls require an active durable run ledger");
            }
            return LlmInvocationHandle.disabled(inputRate, outputRate, maxTokens);
        }
        LocalDateTime startedAt = LocalDateTime.now();
        int invocationSeq = context.getAgentRunState().nextInvocationSeq();
        Long invocationId = context.getExecutionRecorder().createLlmInvocation(LlmInvocationStartRecord.builder()
                .runId(context.getAgentRunState().getRunId())
                .requestId(context.getRequestId())
                .invocationSeq(invocationSeq)
                .agentName(context.getAgentRunState().getCurrentAgentName())
                .stepNo(context.getAgentRunState().getCurrentStepNo())
                .callKind(callKind)
                .streaming(stream)
                .modelName(model)
                .inputRateSnapshot(inputRate)
                .outputRateSnapshot(outputRate)
                .startedAt(startedAt)
                .build());
        if (invocationId == null && context.getOwnerId() != null) {
            throw new IllegalStateException(
                    "authenticated LLM invocation ledger insert failed before quota reservation");
        }
        context.getAgentRunState().bindCurrentLlmInvocationId(invocationId);
        return new LlmInvocationHandle(invocationId, inputRate, outputRate, maxTokens);
    }

    private void reserveLlmCall(AgentContext context,
                                LlmInvocationHandle handle,
                                List<Message> requestMessages,
                                ToolCollection tools) {
        int estimatedInputTokens = estimateInputTokens(requestMessages, tools);
        handle.estimatedInputTokens = estimatedInputTokens;
        if (context == null || context.getOwnerId() == null) {
            return;
        }
        QuotaBillingPort billingPort = runtimeDependencies.getQuotaBillingPort();
        if (billingPort == null) {
            throw new IllegalStateException("QuotaBillingPort must be configured for authenticated LLM calls");
        }
        if (handle == null || handle.invocationId == null) {
            throw new IllegalStateException(
                    "authenticated LLM calls require a persisted invocation ledger ID");
        }

        LlmQuotaCalculator.ReservationAmounts amounts = LlmQuotaCalculator.reservation(
                estimatedInputTokens, maxTokens, handle.inputRateSnapshot, handle.outputRateSnapshot);
        String billingRequestId = context.getRequestId() + ":llm:" + handle.invocationId;
        try {
            handle.reservation = billingPort.reserve(context.getOwnerId(), amounts.requestedMicrocredits(),
                    amounts.minimumMicrocredits(), billingRequestId);
            handle.maxOutputTokens = LlmQuotaCalculator.affordableOutputTokens(
                    handle.reservation.reservedMicrocredits(), estimatedInputTokens, maxTokens,
                    handle.inputRateSnapshot, handle.outputRateSnapshot);
            if (handle.maxOutputTokens < LlmQuotaCalculator.MIN_OUTPUT_TOKENS) {
                billingPort.release(handle.reservation.freezeId());
                handle.reservation = null;
                throw new IllegalStateException("额度不足，无法支持最少256个输出Token");
            }
        } catch (RuntimeException e) {
            recordReservationFailure(context, handle, e);
            throw e;
        }
    }

    private void markLlmProviderStarted(AgentContext context, LlmInvocationHandle handle) {
        if (handle == null || handle.reservation == null) {
            return;
        }
        try {
            runtimeDependencies.getQuotaBillingPort().markProviderStarted(handle.reservation.freezeId());
        } catch (QuotaProviderAlreadyStartedException duplicateAdmission) {
            handle.reservation = null;
            recordReservationFailure(context, handle, duplicateAdmission);
            throw duplicateAdmission;
        } catch (RuntimeException failure) {
            try {
                runtimeDependencies.getQuotaBillingPort().releaseWithUsage(
                        handle.reservation.freezeId(),
                        new QuotaBillingPort.UsageMetadata(
                                handle.invocationId,
                                handle.inputRateSnapshot,
                                handle.outputRateSnapshot,
                                0,
                                0,
                                "PROVIDER_NOT_STARTED",
                                0L));
                handle.reservation = null;
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            recordReservationFailure(context, handle, failure);
            throw failure;
        }
    }

    private int estimateInputTokens(List<Message> messages, ToolCollection tools) {
        int tokens = tokenCounter.countListMessageTokens(formatMessages(messages, isClaudeModel()));
        return Math.addExact(tokens, estimateToolTokens(tools));
    }

    private int estimateToolTokens(ToolCollection tools) {
        int tokens = 0;
        if (tools == null) {
            return tokens;
        }
        for (BaseTool tool : tools.getToolMap().values()) {
            tokens = Math.addExact(tokens, tokenCounter.countText(JSON.toJSONString(Map.of(
                    "name", tool.getName(),
                    "description", StringUtils.defaultString(tool.getDescription()),
                    "parameters", normalizeToolParameters(tool.toParams(), tool.getName())))));
        }
        for (McpToolInfo tool : tools.getMcpToolMap().values()) {
            tokens = Math.addExact(tokens, tokenCounter.countText(JSON.toJSONString(Map.of(
                    "name", tool.resolveExposedName(),
                    "description", StringUtils.defaultString(tool.getDesc()),
                    "parameters", parseAndNormalizeToolParameters(tool.getParameters(), tool.resolveExposedName())))));
        }
        return tokens;
    }

    private void recordReservationFailure(AgentContext context, LlmInvocationHandle handle, RuntimeException error) {
        if (context == null || !handle.enabled()) {
            return;
        }
        context.getExecutionRecorder().finishLlmInvocation(LlmInvocationFinishRecord.builder()
                .llmInvocationId(handle.invocationId)
                .requestId(context.getRequestId())
                .status(ExecutionLedgerConstants.STATUS_FAILED)
                .promptTokens(handle.estimatedInputTokens)
                .completionTokens(0)
                .totalTokens(handle.estimatedInputTokens)
                .usageSource("ESTIMATED")
                .chargedMicrocredits(0L)
                .errorMsg(error.getMessage())
                .finishedAt(LocalDateTime.now())
                .build());
        handle.ledgerFinished = true;
    }

    private void finishLlmInvocation(AgentContext context,
                                     LlmInvocationHandle handle,
                                     ToolCallResponse response,
                                     Throwable throwable) {
        if (throwable != null) {
            finishLlmInvocation(
                    context,
                    handle,
                    ExecutionLedgerConstants.resolveFailureStatus(throwable),
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    throwable.getMessage()
            );
            return;
        }
        handle.estimatedOutputTokens = tokenCounter.countText(response == null ? null : response.getContent())
                + tokenCounter.countText(response == null ? null : JSON.toJSONString(response.getToolCalls()));
        finishLlmInvocation(
                context,
                handle,
                ExecutionLedgerConstants.STATUS_SUCCESS,
                response == null ? null : response.getContent(),
                response == null || response.getToolCalls() == null ? 0 : response.getToolCalls().size(),
                response == null ? null : response.getPromptTokens(),
                response == null ? null : response.getCompletionTokens(),
                response == null ? null : response.getTotalTokens(),
                response == null ? null : response.getFinishReason(),
                null
        );
    }

    private void finishLlmInvocation(AgentContext context,
                                     LlmInvocationHandle handle,
                                     Integer status,
                                     String responseText,
                                     Integer toolCallCount,
                                     Integer promptTokens,
                                     Integer completionTokens,
                                     Integer totalTokens,
                                     String finishReason,
                                     String errorMsg) {
        if (context == null || handle == null) {
            return;
        }
        BillingSettlement billing = settleLlmCall(
                handle,
                promptTokens,
                completionTokens,
                responseText,
                toolCallCount,
                Objects.equals(ExecutionLedgerConstants.STATUS_SUCCESS, status));
        promptTokens = billing.promptTokens();
        completionTokens = billing.completionTokens();
        totalTokens = promptTokens + completionTokens;
        RuntimeException settlementFailure = billing.failure();
        if (!handle.usageRecorded && context.getAgentRunState() != null) {
            context.getAgentRunState().recordLlmUsage(totalTokens, billing.chargedMicrocredits());
            handle.usageRecorded = true;
        }
        if (handle.enabled() && handle.invocationId != null && !handle.ledgerFinished) {
            context.getExecutionRecorder().finishLlmInvocation(LlmInvocationFinishRecord.builder()
                    .llmInvocationId(handle.invocationId)
                    .requestId(context.getRequestId())
                    .status(settlementFailure == null ? status : ExecutionLedgerConstants.STATUS_FAILED)
                    .responseText(responseText)
                    .toolCallCount(toolCallCount)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .usageSource(billing.usageSource())
                    .chargedMicrocredits(billing.chargedMicrocredits())
                    .finishReason(finishReason)
                    .errorMsg(settlementFailure == null ? errorMsg : settlementFailure.getMessage())
                    .finishedAt(LocalDateTime.now())
                    .build());
            handle.ledgerFinished = true;
        }
        if (settlementFailure != null) {
            throw settlementFailure;
        }
    }

    private BillingSettlement settleLlmCall(LlmInvocationHandle handle,
                                             Integer providerPromptTokens,
                                             Integer providerCompletionTokens,
                                             String responseText,
                                             Integer toolCallCount,
                                             boolean callSucceeded) {
        if (handle.settled) {
            return handle.settlement;
        }
        int estimatedOutput = handle.estimatedOutputTokens >= 0
                ? handle.estimatedOutputTokens
                : estimateOutputTokens(responseText, toolCallCount);
        Integer effectiveProviderPromptTokens = providerPromptTokens != null
                ? providerPromptTokens
                : handle.observedProviderPromptTokens;
        Integer effectiveProviderCompletionTokens = providerCompletionTokens != null
                ? providerCompletionTokens
                : handle.observedProviderCompletionTokens;
        LlmUsageSettlement.Result resolved = callSucceeded
                ? LlmUsageSettlement.resolve(
                        effectiveProviderPromptTokens,
                        effectiveProviderCompletionTokens,
                        handle.estimatedInputTokens,
                        estimatedOutput,
                        handle.inputRateSnapshot,
                        handle.outputRateSnapshot)
                : LlmUsageSettlement.resolveFailure(
                        effectiveProviderPromptTokens,
                        effectiveProviderCompletionTokens,
                        handle.estimatedInputTokens,
                        estimatedOutput,
                        handle.inputRateSnapshot,
                        handle.outputRateSnapshot,
                        handle.providerActivityObserved);
        if (handle.reservation != null) {
            try {
                LlmQuotaSettlementExecutor.apply(
                        runtimeDependencies.getQuotaBillingPort(),
                        handle.reservation,
                        resolved,
                        new QuotaBillingPort.UsageMetadata(
                                handle.invocationId,
                                handle.inputRateSnapshot,
                                handle.outputRateSnapshot,
                                resolved.inputTokens(),
                                resolved.outputTokens(),
                                resolved.usageSource(),
                                resolved.chargedMicrocredits()));
            } catch (RuntimeException settlementFailure) {
                handle.settlement = new BillingSettlement(
                        resolved.inputTokens(), resolved.outputTokens(), resolved.usageSource(), 0L, settlementFailure);
                handle.settled = true;
                return handle.settlement;
            }
        }
        handle.settlement = new BillingSettlement(
                resolved.inputTokens(), resolved.outputTokens(), resolved.usageSource(),
                resolved.chargedMicrocredits(), null);
        handle.settled = true;
        return handle.settlement;
    }

    private int estimateOutputTokens(String responseText, Integer toolCallCount) {
        int tokens = tokenCounter.countText(responseText);
        // Tool names/arguments are part of responseText only on struct_parse. Native tool calls are
        // represented separately, so retain a small format allowance when their details are unavailable.
        return Math.addExact(tokens, Math.max(0, toolCallCount == null ? 0 : toolCallCount) * 4);
    }

    private void observePartialOutput(LlmInvocationHandle handle, String partialOutput) {
        int observedTokens = tokenCounter.countText(partialOutput);
        handle.estimatedOutputTokens = observedTokens;
        if (observedTokens > 0) {
            handle.providerActivityObserved = true;
        }
    }

    private void observeProviderResponse(LlmInvocationHandle handle, ChatResponse response) {
        if (handle == null || response == null) {
            return;
        }
        handle.observedProviderPromptTokens = resolvePromptTokens(response.getMetadata());
        handle.observedProviderCompletionTokens = resolveCompletionTokens(response.getMetadata());
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        org.springframework.ai.chat.messages.AssistantMessage output = response.getResult().getOutput();
        if (StringUtils.isNotBlank(output.getText())
                || (output.getToolCalls() != null && !output.getToolCalls().isEmpty())) {
            handle.providerActivityObserved = true;
        }
    }

    private long positiveRate(long configured, long fallback) {
        return configured > 0 ? configured : fallback;
    }

    private <S, T> CompletableFuture<T> mapWithCancellation(CompletableFuture<S> source,
                                                             Function<S, T> mapper) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.whenComplete((ignored, throwable) -> {
            if (!source.isDone()) {
                source.cancel(true);
            }
        });
        source.whenComplete((value, throwable) -> {
            if (result.isDone()) {
                return;
            }
            if (throwable != null) {
                Throwable cause = unwrapCompletionThrowable(throwable);
                if (source.isCancelled() || cause instanceof CancellationException) {
                    result.cancel(false);
                } else {
                    result.completeExceptionally(cause);
                }
                return;
            }
            try {
                result.complete(mapper.apply(value));
            } catch (Throwable mappingError) {
                result.completeExceptionally(mappingError);
            }
        });
        return result;
    }

    private Throwable unwrapCompletionThrowable(Throwable throwable) {
        if ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
                && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private <T> CompletableFuture<T> failedFuture(Exception e) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(e);
        return future;
    }

    /**
     * 深拷贝，保留给 Claude 工具格式转换使用。
     */
    public <T> T deepCopy(T original) {
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(original);
            return objectMapper.readValue(
                    jsonBytes,
                    objectMapper.getTypeFactory().constructType(original.getClass())
            );
        } catch (Exception e) {
            throw new RuntimeException("深拷贝失败", e);
        }
    }

    private Map<String, Object> addFunctionNameParam(Map<String, Object> parameters, String toolName) {
        Map<String, Object> newParameters = deepCopy(parameters);
        ArrayList<String> newRequired = new ArrayList<>();
        newRequired.add("function_name");
        if (parameters.containsKey("required") && parameters.get("required") != null) {
            newRequired.addAll((List<String>) parameters.get("required"));
        }
        newParameters.put("required", newRequired);

        Map<String, Object> newProperties = new HashMap<>();
        Map<String, Object> functionNameMap = new HashMap<>();
        functionNameMap.put("description", "默认值为工具名: " + toolName);
        functionNameMap.put("type", "string");
        newProperties.put("function_name", functionNameMap);
        if (parameters.containsKey("properties") && parameters.get("properties") != null) {
            newProperties.putAll((Map<String, Object>) parameters.get("properties"));
        }
        newParameters.put("properties", newProperties);
        return newParameters;
    }

    private Map<String, Object> normalizeToolParameters(Map<String, Object> rawParameters, String toolName) {
        return ToolSchemaNormalizer.normalizeSchema(rawParameters, toolName);
    }

    private Map<String, Object> parseAndNormalizeToolParameters(String rawParameters, String toolName) {
        return ToolSchemaNormalizer.normalizeSchemaAsMap(rawParameters, toolName);
    }

    private List<String> findMatches(String text, Pattern pattern) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        Matcher matcher = pattern.matcher(text);
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }
        return matches;
    }

    private ToolCall parseToolCall(AgentContext context, String jsonContent) {
        try {
            JSONObject jsonObj = JSON.parseObject(jsonContent);
            String toolName = jsonObj.getString("function_name");
            jsonObj.remove("function_name");
            return ToolCall.builder()
                    .id(StringUtil.getUUID())
                    .type(FUNCTION)
                    .function(ToolCall.Function.builder()
                            .name(toolName)
                            .arguments(JSON.toJSONString(jsonObj))
                            .build())
                    .build();
        } catch (Exception e) {
            log.error("{} parse tool call error {}", context.getRequestId(), jsonContent, e);
            return null;
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ToolCallResponse {
        private String content;
        private List<ToolCall> toolCalls;
        private String streamMessageId;
        private String finishReason;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        private long duration;
    }

    private static final class LlmInvocationHandle {
        private final Long invocationId;
        private final long inputRateSnapshot;
        private final long outputRateSnapshot;
        private int estimatedInputTokens;
        private int estimatedOutputTokens = -1;
        private int maxOutputTokens;
        private Integer observedProviderPromptTokens;
        private Integer observedProviderCompletionTokens;
        private boolean providerActivityObserved;
        private QuotaBillingPort.Reservation reservation;
        private boolean settled;
        private boolean ledgerFinished;
        private boolean usageRecorded;
        private BillingSettlement settlement;

        private LlmInvocationHandle(Long invocationId,
                                    long inputRateSnapshot,
                                    long outputRateSnapshot,
                                    int maxOutputTokens) {
            this.invocationId = invocationId;
            this.inputRateSnapshot = inputRateSnapshot;
            this.outputRateSnapshot = outputRateSnapshot;
            this.maxOutputTokens = maxOutputTokens;
        }

        private static LlmInvocationHandle disabled(long inputRateSnapshot,
                                                    long outputRateSnapshot,
                                                    int maxOutputTokens) {
            return new LlmInvocationHandle(null, inputRateSnapshot, outputRateSnapshot, maxOutputTokens);
        }

        private boolean enabled() {
            return invocationId != null;
        }

        private int maxOutputTokens() {
            return maxOutputTokens;
        }
    }

    private record BillingSettlement(int promptTokens,
                                     int completionTokens,
                                     String usageSource,
                                     long chargedMicrocredits,
                                     RuntimeException failure) {
    }

    private static ReactorRuntimeDependencies requireRuntimeDependencies(ReactorRuntimeDependencies dependencies) {
        if (dependencies == null) {
            throw new IllegalArgumentException("ReactorRuntimeDependencies must not be null");
        }
        return dependencies;
    }
}
