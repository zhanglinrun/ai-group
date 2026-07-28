package com.linrun.agent.domain.agent.runtime.llm;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一装配 OpenAiChatOptions，显式映射标准字段并透传剩余 extraBody。
 */
@Component
public class OpenAiChatOptionsFactory {

    @Resource
    private LlmToolCallbackProvider toolCallbackProvider;

    /**
     * 构造纯文本调用所需的 ChatOptions。
     */
    public OpenAiChatOptions buildTextOptions(LLMSettings settings, Double overrideTemperature) {
        return buildTextOptions(settings, overrideTemperature, settings.getMaxTokens());
    }

    public OpenAiChatOptions buildTextOptions(LLMSettings settings,
                                              Double overrideTemperature,
                                              int maxOutputTokens) {
        OpenAiChatOptions.Builder builder = baseBuilder(settings, overrideTemperature, maxOutputTokens);
        applyExtParams(builder, settings.getExtParams());
        builder.maxTokens(maxOutputTokens);
        return builder.build();
    }

    /**
     * 构造工具调用所需的 ChatOptions。
     */
    public OpenAiChatOptions buildToolOptions(LLMSettings settings,
                                              Double overrideTemperature,
                                              ToolCollection tools,
                                              ToolChoice toolChoice) {
        return buildToolOptions(settings, overrideTemperature, tools, toolChoice, settings.getMaxTokens());
    }

    public OpenAiChatOptions buildToolOptions(LLMSettings settings,
                                              Double overrideTemperature,
                                              ToolCollection tools,
                                              ToolChoice toolChoice,
                                              int maxOutputTokens) {
        return buildToolOptions(settings, overrideTemperature, tools, toolChoice, null, maxOutputTokens);
    }

    public OpenAiChatOptions buildToolOptions(LLMSettings settings,
                                              Double overrideTemperature,
                                              ToolCollection tools,
                                              ToolChoice toolChoice,
                                              String requiredToolName,
                                              int maxOutputTokens) {
        OpenAiChatOptions.Builder builder = baseBuilder(settings, overrideTemperature, maxOutputTokens);
        applyExtParams(builder, settings.getExtParams());
        builder.maxTokens(maxOutputTokens);

        List<ToolCallback> callbacks = toolCallbackProvider.buildToolCallbacks(tools);
        if (!callbacks.isEmpty()) {
            builder.toolCallbacks(callbacks);
            if (toolChoice != null) {
                builder.toolChoice(resolveProviderToolChoice(toolChoice, requiredToolName));
            }
        }
        // Spring AI 只负责产出 tool calls，执行仍由 Agent 控制。
        builder.internalToolExecutionEnabled(false);
        return builder.build();
    }

    static Object resolveProviderToolChoice(ToolChoice toolChoice, String requiredToolName) {
        if (toolChoice == ToolChoice.REQUIRED && StringUtils.isNotBlank(requiredToolName)) {
            return Map.of("type", "function", "function", Map.of("name", requiredToolName));
        }
        return toolChoice == null ? null : toolChoice.getValue();
    }

    private OpenAiChatOptions.Builder baseBuilder(LLMSettings settings,
                                                  Double overrideTemperature,
                                                  int maxOutputTokens) {
        return OpenAiChatOptions.builder()
                .model(settings.getModel())
                .maxTokens(maxOutputTokens)
                .temperature(overrideTemperature != null ? overrideTemperature : settings.getTemperature());
    }

    /**
     * 显式映射常用标准参数，并将剩余字段透传到 extraBody。
     */
    private void applyExtParams(OpenAiChatOptions.Builder builder, Map<String, Object> extParams) {
        if (extParams == null || extParams.isEmpty()) {
            return;
        }

        Map<String, Object> extraBody = new LinkedHashMap<>(extParams);

        Double temperature = removeDouble(extraBody, "temperature");
        if (temperature != null) {
            builder.temperature(temperature);
        }

        Integer maxTokens = removeInteger(extraBody, "max_tokens", "maxTokens");
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }

        Integer maxCompletionTokens = removeInteger(extraBody, "max_completion_tokens", "maxCompletionTokens");
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }

        Double topP = removeDouble(extraBody, "top_p", "topP");
        if (topP != null) {
            builder.topP(topP);
        }

        Double frequencyPenalty = removeDouble(extraBody, "frequency_penalty", "frequencyPenalty");
        if (frequencyPenalty != null) {
            builder.frequencyPenalty(frequencyPenalty);
        }

        Double presencePenalty = removeDouble(extraBody, "presence_penalty", "presencePenalty");
        if (presencePenalty != null) {
            builder.presencePenalty(presencePenalty);
        }

        Boolean parallelToolCalls = removeBoolean(extraBody, "parallel_tool_calls", "parallelToolCalls");
        if (parallelToolCalls != null) {
            builder.parallelToolCalls(parallelToolCalls);
        }

        Integer seed = removeInteger(extraBody, "seed");
        if (seed != null) {
            builder.seed(seed);
        }

        String reasoningEffort = removeString(extraBody, "reasoning_effort", "reasoningEffort");
        if (StringUtils.isNotBlank(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort);
        }

        String verbosity = removeString(extraBody, "verbosity");
        if (StringUtils.isNotBlank(verbosity)) {
            builder.verbosity(verbosity);
        }

        String serviceTier = removeString(extraBody, "service_tier", "serviceTier");
        if (StringUtils.isNotBlank(serviceTier)) {
            builder.serviceTier(serviceTier);
        }

        String user = removeString(extraBody, "user");
        if (StringUtils.isNotBlank(user)) {
            builder.user(user);
        }

        Boolean store = removeBoolean(extraBody, "store");
        if (store != null) {
            builder.store(store);
        }

        List<String> stop = removeStringList(extraBody, "stop", "stop_sequences", "stopSequences");
        if (!stop.isEmpty()) {
            builder.stop(stop);
        }

        if (!extraBody.isEmpty()) {
            builder.extraBody(extraBody);
        }
    }

    private Double removeDouble(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return Double.parseDouble(stringValue.trim());
        }
        return null;
    }

    private Integer removeInteger(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return Integer.parseInt(stringValue.trim());
        }
        return null;
    }

    private Boolean removeBoolean(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return null;
    }

    private String removeString(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> removeStringList(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> listValue) {
            List<String> result = new ArrayList<>();
            for (Object item : listValue) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return List.of(stringValue);
        }
        return List.of();
    }

    private Object removeFirst(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.remove(key);
            }
        }
        return null;
    }
}
