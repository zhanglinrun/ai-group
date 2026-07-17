package com.linrun.agent.domain.agent.adapter.port;

import com.linrun.agent.domain.agent.model.valobj.AiClientModelVO;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;

import java.util.List;

/**
 * 模型目录端口。
 * 面向"管理端配置模型 URL/Key，用户按 modelId 选择模型"的能力：
 * 以启用的 {@code ai_client_model} + 启用的 {@code ai_client_api} 为事实来源，
 * 对外只暴露安全的模型清单，以及把 modelId/modelName 解析为运行时 {@link LLMSettings} 的能力。
 * 由 infrastructure 适配 DAO 实现，并对结果做短 TTL 缓存。
 */
public interface ModelCatalogPort {

    /**
     * 列出对用户可见的可用模型（仅启用、且其 API 也启用）。
     * 返回值只携带 modelId / modelName / modelType，绝不包含 apiKey / baseUrl。
     */
    List<AiClientModelVO> listAvailableModels();

    /**
     * 将 modelId（优先）或 modelName 解析为运行时 LLM 配置。
     *
     * @param modelKey modelId 或 modelName
     * @return 命中且可用返回 {@link LLMSettings}；不可用或不存在返回 {@code null}
     */
    LLMSettings resolveLlmSettings(String modelKey);

    /**
     * 校验 modelId 是否为当前可用模型（用户选择白名单校验）。
     */
    boolean isModelAvailable(String modelId);

    /**
     * 使目录缓存失效。管理端增改删模型 / API 后调用，避免"改库不生效"。
     */
    void invalidate();
}
