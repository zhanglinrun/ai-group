package com.linrun.agent.domain.agent.runtime.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * LLM 配置类。
 * 与 application-dev.yml 中 llm.settings 的 JSON 键一致：使用 snake_case（base_url 等），通过 @JSONField 映射到 camelCase 字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMSettings {
    private String model;

    @JsonProperty("max_tokens")
    private int maxTokens;

    private double temperature;

    private String apiType;

    @JsonProperty("apikey")
    private String apiKey;

    private String apiVersion;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("interface_url")
    private String interfaceUrl;

    private String functionCallType;

    @JsonProperty("max_input_tokens")
    private int maxInputTokens;

    /** Credits charged per one million input tokens. */
    @Builder.Default
    @JsonProperty("input_credits_per_million")
    private long inputCreditsPerMillion = 5L;

    /** Credits charged per one million output tokens. */
    @Builder.Default
    @JsonProperty("output_credits_per_million")
    private long outputCreditsPerMillion = 30L;

    private Map<String, Object> extParams;
}
