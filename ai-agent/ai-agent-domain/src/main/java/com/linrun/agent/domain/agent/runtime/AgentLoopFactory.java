package com.linrun.agent.domain.agent.runtime;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;

import java.util.List;
import java.util.Objects;

/**
 * Immutable production assembly for run-local Agent Loop instances.
 * Spring extensions are captured as definitions, while the mutable HookBus and AgentLoop
 * are recreated for every run.
 */
public final class AgentLoopFactory {

    private final PermissionPolicy permissionPolicy;
    private final List<HookBus.Hook> hooks;
    private final List<RunCustomizer> customizers;

    public AgentLoopFactory(PermissionPolicy permissionPolicy,
                            List<HookBus.Hook> hooks,
                            List<RunCustomizer> customizers) {
        this.permissionPolicy = Objects.requireNonNullElseGet(
                permissionPolicy, DefaultPermissionPolicy::new);
        this.hooks = immutableNonNull(hooks);
        this.customizers = immutableNonNull(customizers);
    }

    /** Default assembly retained for direct domain tests and non-Spring callers. */
    public static AgentLoopFactory defaults() {
        return new AgentLoopFactory(new DefaultPermissionPolicy(), List.of(), List.of());
    }

    public AgentLoop create(AgentContext context) {
        Objects.requireNonNull(context, "AgentContext must not be null");
        HookBus runHookBus = new HookBus();
        hooks.forEach(runHookBus::register);

        AgentLoop agentLoop = new AgentLoop(context, permissionPolicy, runHookBus);
        for (RunCustomizer customizer : customizers) {
            customizer.customize(agentLoop, context);
        }
        return agentLoop;
    }

    private static <T> List<T> immutableNonNull(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** Invoked once against each newly-created loop; implementations must not reuse run state. */
    @FunctionalInterface
    public interface RunCustomizer {
        void customize(AgentLoop agentLoop, AgentContext context);
    }
}
