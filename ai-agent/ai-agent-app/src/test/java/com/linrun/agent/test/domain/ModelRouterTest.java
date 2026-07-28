package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.runtime.llm.ModelRouter;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import java.util.List;

public class ModelRouterTest {

    @Test
    public void shouldApplyFixedRoutingPriority() {
        ModelRouter router = router();

        Assert.assertEquals("deep", router.route(context(AgentExecutionProfile.DEEP, "html", true, true)));
        Assert.assertEquals("report", router.route(context(AgentExecutionProfile.STANDARD, "docs", true, true)));
        Assert.assertEquals("tools", router.route(context(AgentExecutionProfile.STANDARD, "chat", true, false)));
        Assert.assertEquals("tools", router.route(context(AgentExecutionProfile.STANDARD, "chat", false, true)));
        Assert.assertEquals("simple", router.route(context(AgentExecutionProfile.STANDARD, "chat", false, false)));
    }

    @Test
    public void shouldPreferExplicitModelIdOverRoutedModel() {
        ModelRouter router = router();
        ModelCatalogPort catalog = Mockito.mock(ModelCatalogPort.class);
        Mockito.when(catalog.resolveLlmSettings("model-id"))
                .thenReturn(LLMSettings.builder().model("explicit-model").build());
        ReactorRuntimeDependencies dependencies = ReactorRuntimeDependencies.builder()
                .reactorConfig(new ReactorConfig())
                .environment(Mockito.mock(Environment.class))
                .modelRouter(router)
                .modelCatalogPort(catalog)
                .build();
        AgentContext context = context(AgentExecutionProfile.DEEP, "html", true, true);
        context.setModelIdOverride("model-id");

        Assert.assertEquals("explicit-model", dependencies.resolveAgentLlmSettings(context).getModel());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectBlankConfiguredModelAtStartup() {
        ModelRouter router = router();
        router.setReport(" ");
        router.afterPropertiesSet();
    }

    private ModelRouter router() {
        ModelRouter router = new ModelRouter();
        router.setSimpleQa("simple");
        router.setToolCalling("tools");
        router.setReport("report");
        router.setDeepSearch("deep");
        return router;
    }

    private AgentContext context(AgentExecutionProfile profile, String outputStyle,
                                 boolean online, boolean hasFiles) {
        return AgentContext.builder()
                .executionProfile(profile)
                .outputStyle(outputStyle)
                .online(online)
                .productFiles(hasFiles ? List.of(new com.linrun.agent.domain.agent.runtime.dto.File()) : List.of())
                .taskProductFiles(List.of())
                .build();
    }
}
