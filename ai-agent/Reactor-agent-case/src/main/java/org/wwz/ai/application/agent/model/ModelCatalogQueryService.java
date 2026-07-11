package org.wwz.ai.application.agent.model;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.adapter.port.ModelCatalogPort;
import org.wwz.ai.domain.agent.model.valobj.AiClientModelVO;

import java.util.List;

/**
 * 模型目录查询应用服务。
 * 直接转发到 domain 的 {@link ModelCatalogPort}（由 infrastructure 适配 DAO + TTL 缓存）。
 */
@Service
@RequiredArgsConstructor
public class ModelCatalogQueryService implements IModelCatalogQueryService {

    private final ModelCatalogPort modelCatalogPort;

    @Override
    public List<AiClientModelVO> listAvailableModels() {
        return modelCatalogPort.listAvailableModels();
    }

    @Override
    public boolean isModelAvailable(String modelId) {
        return modelCatalogPort.isModelAvailable(modelId);
    }

    @Override
    public void invalidateCatalog() {
        modelCatalogPort.invalidate();
    }
}
