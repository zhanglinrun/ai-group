package com.linrun.agent.domain.agent.runtime.metrics;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 run 的展示级元数据构造器。
 * 随 SSE 最终帧 result 的 resultMap.metrics 下发，供前端在回复下方展示"模型 / tokens / 耗时"chips。
 * 只放入有效字段（空模型名、非正数 token/耗时会被跳过），避免前端露空 chip。
 */
public final class AgentRunMetrics {

    public static final String KEY = "metrics";
    public static final String MODEL_NAME = "modelName";
    public static final String TOTAL_TOKENS = "totalTokens";
    public static final String DURATION_MS = "durationMs";
    public static final String COMPLETION_ATTEMPTS = "completionAttempts";
    public static final String COMPLETION_BLOCKED = "completionBlocked";
    public static final String FINAL_VERIFIER_COUNT = "finalVerifierCount";
    public static final String TOOL_CATALOG_COUNT = "toolCatalogCount";
    public static final String EXPOSED_TOOL_COUNT = "exposedToolCount";
    public static final String DEFERRED_TOOL_COUNT = "deferredToolCount";
    public static final String TOOL_SCHEMA_CHARS = "toolSchemaChars";
    public static final String TOOL_CALL_COUNT = "toolCallCount";
    public static final String CHARGED_MICROCREDITS = "chargedMicrocredits";

    private AgentRunMetrics() {
    }

    public static Map<String, Object> of(String modelName, Long totalTokens, Long durationMs) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(modelName)) {
            metrics.put(MODEL_NAME, modelName.trim());
        }
        if (totalTokens != null && totalTokens > 0) {
            metrics.put(TOTAL_TOKENS, totalTokens);
        }
        if (durationMs != null && durationMs >= 0) {
            metrics.put(DURATION_MS, durationMs);
        }
        return metrics;
    }

    /**
     * 从 AgentContext 构建 Agent Loop 展示级 metrics（模型名 + 估算耗时）。
     * 模型名优先取用户覆盖后解析的有效模型，回退到静态配置模型名；耗时从 run 起始时间估算。
     * 流式链路暂不可靠地统计 token，故此处不含 token（历史侧从账本补全，后续迭代）。
     *
     * @param context           当前 run 上下文
     * @param fallbackModelName 静态配置模型名（如 agent-loop 的 model_name）
     */
    public static Map<String, Object> fromContext(AgentContext context, String fallbackModelName) {
        String modelName = fallbackModelName;
        Long durationMs = null;
        if (context != null) {
            if (context.getRunStartedAtMillis() != null) {
                durationMs = Math.max(0L, System.currentTimeMillis() - context.getRunStartedAtMillis());
            }
            try {
                if (context.getRuntimeDependencies() != null) {
                    LLMSettings settings = context.getRuntimeDependencies()
                            .resolveEffectiveLlmSettings(context.getModelIdOverride(), fallbackModelName);
                    if (settings != null && StringUtils.isNotBlank(settings.getModel())) {
                        modelName = settings.getModel();
                    }
                }
            } catch (Exception ignore) {
                // 模型名解析失败不影响主流程，退回 fallback
            }
        }
        Long totalTokens = context == null || context.getAgentRunState() == null
                ? null
                : context.getAgentRunState().getTotalTokenCountValue();
        Map<String, Object> metrics = of(modelName, totalTokens, durationMs);
        if (context != null && context.getAgentRunState() != null) {
            int completionAttempts = context.getAgentRunState().getCompletionAttemptCountValue();
            int completionBlocked = context.getAgentRunState().getCompletionBlockedCountValue();
            int verifierCount = context.getAgentRunState().getFinalVerifierCountValue();
            if (completionAttempts > 0) {
                metrics.put(COMPLETION_ATTEMPTS, completionAttempts);
            }
            if (completionBlocked > 0) {
                metrics.put(COMPLETION_BLOCKED, completionBlocked);
            }
            if (verifierCount > 0) {
                metrics.put(FINAL_VERIFIER_COUNT, verifierCount);
            }
            int catalogCount = context.getAgentRunState().getLatestToolCatalogCountValue();
            int exposedCount = context.getAgentRunState().getLatestExposedToolCountValue();
            int deferredCount = context.getAgentRunState().getLatestDeferredToolCountValue();
            int schemaChars = context.getAgentRunState().getLatestToolSchemaCharsValue();
            if (catalogCount > 0) {
                metrics.put(TOOL_CATALOG_COUNT, catalogCount);
                metrics.put(EXPOSED_TOOL_COUNT, exposedCount);
            }
            if (deferredCount > 0) {
                metrics.put(DEFERRED_TOOL_COUNT, deferredCount);
            }
            if (schemaChars > 0) {
                metrics.put(TOOL_SCHEMA_CHARS, schemaChars);
            }
            int toolCallCount = context.getAgentRunState().getToolCallCountValue();
            long chargedMicrocredits = context.getAgentRunState().getChargedMicrocreditsValue();
            if (toolCallCount > 0) {
                metrics.put(TOOL_CALL_COUNT, toolCallCount);
            }
            if (chargedMicrocredits > 0) {
                metrics.put(CHARGED_MICROCREDITS, chargedMicrocredits);
            }
        }
        return metrics;
    }
}
