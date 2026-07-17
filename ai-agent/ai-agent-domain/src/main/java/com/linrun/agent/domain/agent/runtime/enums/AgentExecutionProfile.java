package com.linrun.agent.domain.agent.runtime.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * Unified agent-loop execution profile.
 * STANDARD keeps completion checks lightweight; AUTO lets the harness escalate
 * to structured todo verification when the model opens a todo list; DEEP
 * requires an explicit todo list and an independent final verification pass.
 */
public enum AgentExecutionProfile {
    STANDARD,
    AUTO,
    DEEP;

    public static AgentExecutionProfile resolve(String value) {
        if (StringUtils.isNotBlank(value)) {
            try {
                return AgentExecutionProfile.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Fall through to the safe default.
            }
        }
        return STANDARD;
    }
}
