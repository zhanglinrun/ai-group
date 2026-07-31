package com.linrun.agent.domain.agent.runtime.context;

import java.util.Locale;

/** The smallest role-specific view that may be projected into one model turn. */
public enum ContextRole {
    STANDARD,
    PLANNER,
    RESEARCHER,
    WRITER,
    REVIEWER,
    TOOL;

    public static ContextRole fromAgentName(String agentName) {
        String normalized = agentName == null ? "" : agentName.toLowerCase(Locale.ROOT);
        if (normalized.contains("planner") || normalized.contains("intake")) {
            return PLANNER;
        }
        if (normalized.contains("research")) {
            return RESEARCHER;
        }
        if (normalized.contains("writer")) {
            return WRITER;
        }
        if (normalized.contains("review")) {
            return REVIEWER;
        }
        if (normalized.contains("tool")) {
            return TOOL;
        }
        return STANDARD;
    }
}
