package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Typed run-local extension points around model, tool and completion boundaries. */
public final class HookBus {

    private final List<Hook> hooks = new CopyOnWriteArrayList<>();

    public void register(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
        }
    }

    public HookDecision fire(HookEvent event) {
        if (event == null) {
            return HookDecision.allow();
        }
        for (Hook hook : hooks) {
            HookDecision decision;
            try {
                decision = hook.handle(event);
            } catch (RuntimeException error) {
                return HookDecision.deny(
                        "Hook failed at " + event.point() + ": " + error.getClass().getSimpleName());
            }
            if (decision != null && !decision.allowed()) {
                return decision;
            }
        }
        return HookDecision.allow();
    }

    @FunctionalInterface
    public interface Hook {
        HookDecision handle(HookEvent event);
    }

    public enum HookPoint {
        PRE_MODEL,
        POST_MODEL,
        PRE_TOOL,
        POST_TOOL,
        TOOL_FAILURE,
        PRE_COMPLETION,
        POST_COMPLETION
    }

    public record HookEvent(HookPoint point,
                            AgentContext context,
                            String capability,
                            Object input,
                            Object output) {
    }

    public record HookDecision(boolean allowed, String reason) {
        public static HookDecision allow() {
            return new HookDecision(true, null);
        }

        public static HookDecision deny(String reason) {
            return new HookDecision(false, reason);
        }
    }
}
