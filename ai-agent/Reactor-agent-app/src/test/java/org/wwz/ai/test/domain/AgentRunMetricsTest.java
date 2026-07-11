package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.adapter.port.ModelCatalogPort;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.metrics.AgentRunMetrics;

import java.util.Map;

public class AgentRunMetricsTest {

    @Test
    public void shouldFilterInvalidDisplayValues() {
        Map<String, Object> metrics = AgentRunMetrics.of(" qwen-plus ", 42L, 0L);
        Assert.assertEquals("qwen-plus", metrics.get(AgentRunMetrics.MODEL_NAME));
        Assert.assertEquals(42L, metrics.get(AgentRunMetrics.TOTAL_TOKENS));
        Assert.assertEquals(0L, metrics.get(AgentRunMetrics.DURATION_MS));
        Assert.assertTrue(AgentRunMetrics.of(" ", 0L, -1L).isEmpty());
    }

    @Test
    public void shouldUseSelectedModelAndComputeDuration() {
        ModelCatalogPort catalog = Mockito.mock(ModelCatalogPort.class);
        Mockito.when(catalog.resolveLlmSettings("model-1"))
                .thenReturn(LLMSettings.builder().model("qwen-max").build());
        ReactorRuntimeDependencies dependencies = ReactorRuntimeDependencies.builder()
                .modelCatalogPort(catalog)
                .build();
        AgentContext context = AgentContext.builder()
                .runtimeDependencies(dependencies)
                .modelIdOverride("model-1")
                .runStartedAtMillis(System.currentTimeMillis() - 20L)
                .build();

        Map<String, Object> metrics = AgentRunMetrics.fromContext(context, "fallback-model");

        Assert.assertEquals("qwen-max", metrics.get(AgentRunMetrics.MODEL_NAME));
        Assert.assertTrue((Long) metrics.get(AgentRunMetrics.DURATION_MS) >= 0L);
        Mockito.verify(catalog).resolveLlmSettings("model-1");
    }
}
