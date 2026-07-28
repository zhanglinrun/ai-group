package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.infrastructure.adapter.port.ModelCatalogAdapter;
import com.linrun.agent.infrastructure.dao.IAiClientApiDao;
import com.linrun.agent.infrastructure.dao.IAiClientModelDao;
import com.linrun.agent.infrastructure.dao.po.AiClientApi;
import com.linrun.agent.infrastructure.dao.po.AiClientModel;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

public class ModelCatalogAdapterTest {

    @Test
    public void shouldUseEnvironmentDefaultWhenCatalogApiKeyIsPlaceholder() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AGENT_GROUP_LLM_API_KEY", "env-key")
                .withProperty("AGENT_GROUP_LLM_BASE_URL", "https://api.xiaoxiong123.cloud/v1")
                .withProperty("AGENT_GROUP_LLM_CHAT_MODEL", "gpt-5.5");

        LLMSettings settings = adapter("not-configured", environment).resolveLlmSettings("dev_model_001");

        Assert.assertEquals("env-key", settings.getApiKey());
        Assert.assertEquals("https://api.xiaoxiong123.cloud/v1", settings.getBaseUrl());
        Assert.assertEquals("gpt-5.5", settings.getModel());
    }

    @Test
    public void shouldKeepCatalogSettingsWhenApiKeyIsConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AGENT_GROUP_LLM_API_KEY", "env-key")
                .withProperty("AGENT_GROUP_LLM_BASE_URL", "https://api.xiaoxiong123.cloud/v1")
                .withProperty("AGENT_GROUP_LLM_CHAT_MODEL", "gpt-5.5");

        LLMSettings settings = adapter("db-key", environment).resolveLlmSettings("dev_model_001");

        Assert.assertEquals("db-key", settings.getApiKey());
        Assert.assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", settings.getBaseUrl());
        Assert.assertEquals("qwen-plus", settings.getModel());
    }

    private ModelCatalogAdapter adapter(String apiKey, MockEnvironment environment) {
        IAiClientApiDao apiDao = Mockito.mock(IAiClientApiDao.class);
        IAiClientModelDao modelDao = Mockito.mock(IAiClientModelDao.class);
        Mockito.when(apiDao.queryEnabledApis()).thenReturn(List.of(api(apiKey)));
        Mockito.when(modelDao.queryEnabledModels()).thenReturn(List.of(model()));

        ModelCatalogAdapter adapter = new ModelCatalogAdapter();
        ReflectionTestUtils.setField(adapter, "aiClientApiDao", apiDao);
        ReflectionTestUtils.setField(adapter, "aiClientModelDao", modelDao);
        ReflectionTestUtils.setField(adapter, "environment", environment);
        return adapter;
    }

    private AiClientApi api(String apiKey) {
        return AiClientApi.builder()
                .apiId("dev_api_001")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(apiKey)
                .completionsPath("/v1/chat/completions")
                .build();
    }

    private AiClientModel model() {
        return AiClientModel.builder()
                .modelId("dev_model_001")
                .apiId("dev_api_001")
                .modelName("qwen-plus")
                .modelType("openai")
                .build();
    }
}
