package com.linrun.agent.domain.agent.runtime.tool;

import com.linrun.agent.domain.agent.runtime.harness.ToolPermissionMetadata;

import java.util.Map;

/**
 * 工具基接口
 */
public interface BaseTool {
    String getName();

    String getDescription();

    Map<String, Object> toParams();

    Object execute(Object input);

    /** Metadata is conservative at the policy boundary without changing legacy read-only tools. */
    default ToolPermissionMetadata permissionMetadata() {
        return ToolPermissionMetadata.readOnly();
    }

    /**
     * Automatic retries are opt-in because many tools create files, execute
     * code, charge credits, or mutate remote state.
     */
    default boolean isRetryable() {
        return false;
    }

    /**
     * Opt in only when two calls can safely overlap without corrupting state,
     * reordering mutations or double-applying side effects.
     */
    default boolean isConcurrencySafe(Object input) {
        return false;
    }

    /**
     * Opt out of run-local successful-operation reuse when identical calls are
     * expected to observe changing state, such as polling or live reads.
     *
     * <p>The safe default is {@code false}: once an identical operation has
     * completed successfully in the current run, the dispatcher reuses that
     * outcome instead of applying the same side effect again.</p>
     */
    default boolean allowRepeatedSuccessfulCall() {
        return false;
    }
}
