package com.linrun.agent.domain.agent.runtime;

import lombok.Builder;
import lombok.Value;
import org.springframework.core.env.Environment;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.runtime.llm.ModelRouter;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.rag.ingest.DocumentIngestRouter;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalGate;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolExecutor;
import com.linrun.agent.domain.agent.reactor.service.imagegeneration.IImageGenerationExecutionKernel;
import org.springframework.scheduling.TaskScheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Reactor 运行时依赖包。
 * domain 侧只依赖这个 typed bundle，不再直接触碰 Spring 容器全局入口。
 */
@Value
@Builder(toBuilder = true)
public class ReactorRuntimeDependencies {

    ReactorConfig reactorConfig;

    Environment environment;

    ReactorLlmDependencies llmDependencies;

    McpToolExecutor mcpToolExecutor;

    IImageGenerationExecutionKernel imageGenerationExecutionKernel;

    RemoteHttpPort remoteHttpPort;

    RemoteStreamPort remoteStreamPort;

    FileArtifactPort fileArtifactPort;

    DocumentIngestRouter documentIngestRouter;

    HybridRetriever hybridRetriever;

    ApprovalGate approvalGate;

    /** Optional only for isolated legacy tests; production binds deep_search/code_interpreter through this boundary. */
    DurableToolExecutor durableToolExecutor;

    ModelRouter modelRouter;

    /** 模型目录端口，供用户按 modelId 覆盖模型时解析 DB 配置。可为空（未装配时回退静态配置）。 */
    ModelCatalogPort modelCatalogPort;

    /** Optional only for isolated tests; production requests with an owner require this port. */
    QuotaBillingPort quotaBillingPort;

    /** Read-only authenticated bridge to account, pricing, group-buy, and order context. */
    PlatformContextPort platformContextPort;

    //预留给之后并发调用llm
    Executor llmExecutor;

    Executor taskExecutor;

    Executor toolExecutor;

    TaskScheduler heartbeatScheduler;

    Long runHeartbeatIntervalMillis;

    public ReactorConfig requireReactorConfig() {
        return Objects.requireNonNull(reactorConfig, "ReactorConfig must not be null");
    }

    public Environment requireEnvironment() {
        return Objects.requireNonNull(environment, "Environment must not be null");
    }

    public ReactorLlmDependencies requireLlmDependencies() {
        return Objects.requireNonNull(llmDependencies, "ReactorLlmDependencies must not be null");
    }

    public McpToolExecutor getOptionalMcpToolExecutor() {
        return mcpToolExecutor;
    }

    public DurableToolExecutor getOptionalDurableToolExecutor() {
        return durableToolExecutor;
    }

    public IImageGenerationExecutionKernel requireImageGenerationExecutionKernel() {
        return Objects.requireNonNull(imageGenerationExecutionKernel, "IImageGenerationExecutionKernel must not be null");
    }

    public RemoteHttpPort requireRemoteHttpPort() {
        return Objects.requireNonNull(remoteHttpPort, "RemoteHttpPort must not be null");
    }

    public RemoteStreamPort requireRemoteStreamPort() {
        return Objects.requireNonNull(remoteStreamPort, "RemoteStreamPort must not be null");
    }

    public FileArtifactPort requireFileArtifactPort() {
        return Objects.requireNonNull(fileArtifactPort, "FileArtifactPort must not be null");
    }

    public PlatformContextPort requirePlatformContextPort() {
        return Objects.requireNonNull(platformContextPort, "PlatformContextPort must not be null");
    }

    public Executor requireLlmExecutor() {
        return Objects.requireNonNull(llmExecutor, "llmExecutor must not be null");
    }

    public Executor requireToolExecutor() {
        return Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    }

    public Executor requireTaskExecutor() {
        return Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
    }

    public TaskScheduler requireHeartbeatScheduler() {
        return Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler must not be null");
    }

    public long effectiveRunHeartbeatIntervalMillis() {
        return runHeartbeatIntervalMillis == null || runHeartbeatIntervalMillis <= 0L
                ? 10_000L
                : runHeartbeatIntervalMillis;
    }

    /**
     * 统一解析 LLM 配置。
     * 查找序：ReactorConfig.llmSettings（按模型名）→ 模型目录（DB，按 modelId/模型名）→ Environment 中的 llm.default.*。
     */
    public LLMSettings resolveLlmSettings(String modelName) {
        ReactorConfig config = requireReactorConfig();
        String normalizedModelName = modelName == null ? "" : modelName.trim();
        if (config.getLlmSettingsMap() != null && !normalizedModelName.isBlank()) {
            LLMSettings settings = config.getLlmSettingsMap().get(normalizedModelName);
            if (settings != null) {
                return settings;
            }
        }

        if (modelCatalogPort != null && !normalizedModelName.isBlank()) {
            LLMSettings fromCatalog = modelCatalogPort.resolveLlmSettings(normalizedModelName);
            if (fromCatalog != null) {
                return fromCatalog;
            }
        }

        LLMSettings defaultConfig = buildDefaultLlmSettings();
        if (!normalizedModelName.isBlank()) {
            defaultConfig.setModel(normalizedModelName);
        }
        return defaultConfig;
    }

    /**
     * 解析本次执行实际生效的 LLM 配置。
     * 用户在对话中显式选择模型（overrideModelId）时，优先按 modelId 从模型目录解析；
     * 未选择或目录解析失败时，回退到按静态配置模型名解析（保持既有默认行为）。
     *
     * @param overrideModelId   用户选择的 modelId（可空）
     * @param fallbackModelName 静态配置的模型名（如 agent-loop 的 model_name）
     */
    public LLMSettings resolveEffectiveLlmSettings(String overrideModelId, String fallbackModelName) {
        String normalizedOverride = overrideModelId == null ? "" : overrideModelId.trim();
        if (!normalizedOverride.isBlank() && modelCatalogPort != null) {
            LLMSettings overrideSettings = modelCatalogPort.resolveLlmSettings(normalizedOverride);
            if (overrideSettings != null) {
                return overrideSettings;
            }
        }
        return resolveLlmSettings(fallbackModelName);
    }

    public LLMSettings resolveAgentLlmSettings(AgentContext context) {
        String fallback = modelRouter == null
                ? requireReactorConfig().getAgentLoopModelName()
                : modelRouter.route(context);
        return resolveEffectiveLlmSettings(context == null ? null : context.getModelIdOverride(), fallback);
    }

    private LLMSettings buildDefaultLlmSettings() {
        Environment env = requireEnvironment();
        return LLMSettings.builder()
                .model(env.getProperty("llm.default.model", "gpt-4o-0806"))
                .maxTokens(parseInt(env.getProperty("llm.default.max_tokens"), 16384))
                .temperature(parseDouble(env.getProperty("llm.default.temperature"), 0.0))
                .baseUrl(env.getProperty("llm.default.base_url", ""))
                .interfaceUrl(env.getProperty("llm.default.interface_url", "/v1/chat/completions"))
                .functionCallType(env.getProperty("llm.default.function_call_type", "function_call"))
                .apiKey(env.getProperty("llm.default.apikey", ""))
                .maxInputTokens(parseInt(env.getProperty("llm.default.max_input_tokens"), 100000))
                .inputCreditsPerMillion(parseLong(env.getProperty("llm.default.input_credits_per_million"), 5L))
                .outputCreditsPerMillion(parseLong(env.getProperty("llm.default.output_credits_per_million"), 30L))
                .extParams(new HashMap<>())
                .build();
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignore) {
            return defaultValue;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignore) {
            return defaultValue;
        }
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignore) {
            return defaultValue;
        }
    }
}
