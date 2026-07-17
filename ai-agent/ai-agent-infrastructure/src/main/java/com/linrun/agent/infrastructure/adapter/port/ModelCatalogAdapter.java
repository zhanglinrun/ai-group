package com.linrun.agent.infrastructure.adapter.port;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.model.valobj.AiClientModelVO;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.infrastructure.dao.IAiClientApiDao;
import com.linrun.agent.infrastructure.dao.IAiClientModelDao;
import com.linrun.agent.infrastructure.dao.po.AiClientApi;
import com.linrun.agent.infrastructure.dao.po.AiClientModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录适配器。
 * 以启用的 {@code ai_client_model} join 启用的 {@code ai_client_api} 为事实来源，
 * 提供用户可见模型清单与 modelId/modelName → {@link LLMSettings} 解析，并做 60s TTL 缓存。
 * 管理端增改删模型/API 后应调用 {@link #invalidate()} 让缓存立即失效。
 */
@Slf4j
@Component
public class ModelCatalogAdapter implements ModelCatalogPort {

    private static final long CACHE_TTL_MILLIS = 60_000L;
    private static final int DEFAULT_MAX_TOKENS = 16384;
    private static final int DEFAULT_MAX_INPUT_TOKENS = 100000;
    private static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    @Resource
    private IAiClientModelDao aiClientModelDao;

    @Resource
    private IAiClientApiDao aiClientApiDao;

    private volatile Snapshot snapshot;

    @Override
    public List<AiClientModelVO> listAvailableModels() {
        return getSnapshot().models();
    }

    @Override
    public LLMSettings resolveLlmSettings(String modelKey) {
        if (StringUtils.isBlank(modelKey)) {
            return null;
        }
        Snapshot current = getSnapshot();
        String key = modelKey.trim();
        LLMSettings byId = current.settingsById().get(key);
        if (byId != null) {
            return byId;
        }
        return current.settingsByName().get(key);
    }

    @Override
    public boolean isModelAvailable(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return false;
        }
        return getSnapshot().settingsById().containsKey(modelId.trim());
    }

    @Override
    public void invalidate() {
        snapshot = null;
        log.info("Model catalog cache invalidated");
    }

    private Snapshot getSnapshot() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && (now - current.loadedAt()) < CACHE_TTL_MILLIS) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            now = System.currentTimeMillis();
            if (current != null && (now - current.loadedAt()) < CACHE_TTL_MILLIS) {
                return current;
            }
            Snapshot rebuilt = rebuild();
            snapshot = rebuilt;
            return rebuilt;
        }
    }

    private Snapshot rebuild() {
        List<AiClientModelVO> models = new ArrayList<>();
        Map<String, LLMSettings> settingsById = new HashMap<>();
        Map<String, LLMSettings> settingsByName = new HashMap<>();
        try {
            List<AiClientApi> enabledApis = aiClientApiDao.queryEnabledApis();
            Map<String, AiClientApi> apiById = new HashMap<>();
            if (enabledApis != null) {
                for (AiClientApi api : enabledApis) {
                    if (api != null && api.getApiId() != null) {
                        apiById.put(api.getApiId(), api);
                    }
                }
            }

            List<AiClientModel> enabledModels = aiClientModelDao.queryEnabledModels();
            if (enabledModels != null) {
                for (AiClientModel model : enabledModels) {
                    if (model == null || model.getModelId() == null) {
                        continue;
                    }
                    AiClientApi api = apiById.get(model.getApiId());
                    if (api == null || StringUtils.isBlank(api.getBaseUrl())) {
                        // 关联 API 未启用或缺少 base_url，跳过，避免暴露不可用模型
                        continue;
                    }
                    models.add(AiClientModelVO.builder()
                            .modelId(model.getModelId())
                            .modelName(model.getModelName())
                            .modelType(model.getModelType())
                            .apiId(model.getApiId())
                            .build());
                    LLMSettings settings = toLlmSettings(model, api);
                    settingsById.put(model.getModelId(), settings);
                    if (StringUtils.isNotBlank(model.getModelName())) {
                        // 模型名相同时以先出现者为准（modelId 唯一，name 允许重复但极少见）
                        settingsByName.putIfAbsent(model.getModelName().trim(), settings);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Rebuild model catalog failed", e);
        }
        return new Snapshot(
                Collections.unmodifiableList(models),
                Collections.unmodifiableMap(settingsById),
                Collections.unmodifiableMap(settingsByName),
                System.currentTimeMillis());
    }

    private LLMSettings toLlmSettings(AiClientModel model, AiClientApi api) {
        return LLMSettings.builder()
                .model(model.getModelName())
                .baseUrl(api.getBaseUrl())
                .apiKey(api.getApiKey())
                .interfaceUrl(StringUtils.isNotBlank(api.getCompletionsPath())
                        ? api.getCompletionsPath()
                        : DEFAULT_COMPLETIONS_PATH)
                .functionCallType("function_call")
                .maxTokens(DEFAULT_MAX_TOKENS)
                .maxInputTokens(DEFAULT_MAX_INPUT_TOKENS)
                .inputCreditsPerMillion(defaultRate(model.getInputCreditsPerMillion(), 5L))
                .outputCreditsPerMillion(defaultRate(model.getOutputCreditsPerMillion(), 30L))
                .temperature(0.0)
                .extParams(new HashMap<>())
                .build();
    }

    private long defaultRate(Long configured, long fallback) {
        return configured != null && configured > 0 ? configured : fallback;
    }

    /** 不可变目录快照，含加载时间戳用于 TTL 判定。 */
    private record Snapshot(List<AiClientModelVO> models,
                            Map<String, LLMSettings> settingsById,
                            Map<String, LLMSettings> settingsByName,
                            long loadedAt) {
    }
}
