package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.config.reactor.AgentLoopFactoryConfiguration;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.loop.ModelGateway;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentLoopFactoryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentLoopFactoryConfiguration.class);

    @Test
    public void shouldProvideDefaultPermissionPolicyWhenApplicationDoesNotOverrideIt() {
        contextRunner.run(context -> {
            Assert.assertNull(context.getStartupFailure());
            AgentLoop loop = context.getBean(AgentLoopFactory.class)
                    .create(newAgentContext("default-policy-run"));
            Assert.assertTrue(loop.getPermissionPolicy() instanceof DefaultPermissionPolicy);
        });
    }

    @Test
    public void shouldInjectExtensionsAndKeepRunStateIsolated() {
        ExtensionConfiguration.CUSTOMIZER_CALLS.set(0);
        contextRunner.withUserConfiguration(ExtensionConfiguration.class).run(context -> {
            Assert.assertNull(context.getStartupFailure());
            Assert.assertEquals(1, context.getBeansOfType(PermissionPolicy.class).size());

            AgentLoopFactory factory = context.getBean(AgentLoopFactory.class);
            PermissionPolicy injectedPolicy = context.getBean(PermissionPolicy.class);
            AgentLoop first = factory.create(newAgentContext("factory-run-1"));
            AgentLoop second = factory.create(newAgentContext("factory-run-2"));

            Assert.assertNotSame(first, second);
            Assert.assertNotSame(first.getHookBus(), second.getHookBus());
            Assert.assertNotSame(first.getMemory(), second.getMemory());
            Assert.assertSame(injectedPolicy, first.getPermissionPolicy());
            Assert.assertSame(injectedPolicy, second.getPermissionPolicy());
            Assert.assertEquals(Integer.valueOf(321), first.getMaxObserve());
            Assert.assertEquals(Integer.valueOf(321), second.getMaxObserve());
            Assert.assertEquals(2, ExtensionConfiguration.CUSTOMIZER_CALLS.get());

            first.getHookBus().register(event -> event.point() == HookBus.HookPoint.PRE_COMPLETION
                    ? HookBus.HookDecision.deny("first-run-only")
                    : HookBus.HookDecision.allow());
            HookBus.HookDecision secondRunDecision = second.getHookBus().fire(new HookBus.HookEvent(
                    HookBus.HookPoint.PRE_COMPLETION,
                    second.getContext(),
                    "completion",
                    null,
                    null));
            Assert.assertTrue("mutating one run's HookBus must not affect the next run",
                    secondRunDecision.allowed());

            ModelGateway blockedGateway = Mockito.mock(ModelGateway.class);
            first.setModelGateway(blockedGateway);
            String answer = first.run("hook should stop before model invocation");

            Assert.assertEquals("Thinking complete - no action needed", answer);
            Assert.assertEquals(AgentState.FINISHED, first.getState());
            Assert.assertEquals(AgentStopReason.MODEL_ERROR, first.getStopReason());
            Assert.assertTrue(first.getContext().isRunFailed());
            Mockito.verifyNoInteractions(blockedGateway);
        });
    }

    private AgentContext newAgentContext(String requestId) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "agentLoopMaxTurns", 4);
        ReflectionTestUtils.setField(config, "agentLoopMaxToolCalls", 8);
        ReflectionTestUtils.setField(config, "agentLoopMaxCompletionAttempts", 3);
        ReflectionTestUtils.setField(config, "agentLoopMaxDurationSeconds", 60L);
        ReflectionTestUtils.setField(config, "agentLoopModelName", "factory-test-model");
        ReflectionTestUtils.setField(config, "maxObserve", "1000");
        ReflectionTestUtils.setField(config, "toolMaxAttempts", 1);
        config.setLLMSettingsMap("{\"factory-test-model\":{\"model\":\"factory-test-model\"," +
                "\"apikey\":\"test-key\",\"base_url\":\"http://localhost\"," +
                "\"max_tokens\":1000,\"max_input_tokens\":4000}}");

        ToolCollection tools = new ToolCollection();
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(requestId + "-session")
                .query("verify factory extensions")
                .printer(Mockito.mock(Printer.class))
                .dateInfo("")
                .basePrompt("")
                .historyDialogue("")
                .toolCollection(tools)
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(ReactorRuntimeTestSupport.runtimeDependencies(config))
                .build();
        tools.setAgentContext(context);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class ExtensionConfiguration {

        private static final AtomicInteger CUSTOMIZER_CALLS = new AtomicInteger();

        @Bean
        PermissionPolicy injectedPermissionPolicy() {
            return (toolName, input, activeTools, context) ->
                    PermissionPolicy.PermissionDecision.deny("injected permission policy");
        }

        @Bean
        HookBus.Hook preModelBlockingHook() {
            return event -> event.point() == HookBus.HookPoint.PRE_MODEL
                    ? HookBus.HookDecision.deny("blocked by injected hook")
                    : HookBus.HookDecision.allow();
        }

        @Bean
        AgentLoopFactory.RunCustomizer maxObserveCustomizer() {
            return (agentLoop, context) -> {
                CUSTOMIZER_CALLS.incrementAndGet();
                agentLoop.setMaxObserve(321);
            };
        }
    }
}
