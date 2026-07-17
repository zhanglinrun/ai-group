package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.runtime.metrics.AgentRunMetrics;

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

    @Test
    public void shouldExposeCompletionAndToolExposureMetrics() {
        AgentContext context = AgentContext.builder()
                .runStartedAtMillis(System.currentTimeMillis())
                .build();
        context.getAgentRunState().recordCompletionAttempt(false, false);
        context.getAgentRunState().recordCompletionAttempt(true, true);
        context.getAgentRunState().recordToolExposure(12, 5, 7, 8_000);

        Map<String, Object> metrics = AgentRunMetrics.fromContext(context, "qwen-plus");

        Assert.assertEquals(2, metrics.get(AgentRunMetrics.COMPLETION_ATTEMPTS));
        Assert.assertEquals(1, metrics.get(AgentRunMetrics.COMPLETION_BLOCKED));
        Assert.assertEquals(1, metrics.get(AgentRunMetrics.FINAL_VERIFIER_COUNT));
        Assert.assertEquals(12, metrics.get(AgentRunMetrics.TOOL_CATALOG_COUNT));
        Assert.assertEquals(5, metrics.get(AgentRunMetrics.EXPOSED_TOOL_COUNT));
        Assert.assertEquals(7, metrics.get(AgentRunMetrics.DEFERRED_TOOL_COUNT));
        Assert.assertEquals(8_000, metrics.get(AgentRunMetrics.TOOL_SCHEMA_CHARS));
    }
}
