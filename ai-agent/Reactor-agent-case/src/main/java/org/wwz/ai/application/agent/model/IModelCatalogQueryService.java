package org.wwz.ai.application.agent.model;

import org.wwz.ai.domain.agent.model.valobj.AiClientModelVO;

import java.util.List;

/**
 * 模型目录查询 seam。
 * Trigger 用它拉取用户可选模型列表并做写后失效；ingress 用它做用户选择模型的白名单校验。
 */
public interface IModelCatalogQueryService {

    /**
     * 列出对用户可见的可用模型（仅 modelId / modelName / modelType，无敏感字段）。
     */
    List<AiClientModelVO> listAvailableModels();

    /**
     * modelId 是否为当前可用模型（白名单校验）。
     */
    boolean isModelAvailable(String modelId);

    /**
     * 失效模型目录缓存（管理端增改删模型/API 后调用）。
     */
    void invalidateCatalog();
}
