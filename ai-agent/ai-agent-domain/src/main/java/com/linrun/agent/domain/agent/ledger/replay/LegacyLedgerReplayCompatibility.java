package com.linrun.agent.domain.agent.ledger.replay;

import java.util.Set;

/**
 * Read-only adapter for ledger identifiers written before the unified Agent Loop migration.
 *
 * <p>This type is deliberately package-private and lives beside the replay projector. Runtime,
 * dispatch and persistence code cannot depend on these identifiers or select a retired execution
 * path. Remove it only after all persisted legacy runs have been retired or migrated.</p>
 */
final class LegacyLedgerReplayCompatibility {

    private static final String ENTRY_PLAN_SOLVE = "plan_solve";
    private static final String ENTRY_REACT = "react";
    private static final String LLM_WORKFLOW = "workflow";
    private static final String LLM_REACT = "react";
    private static final Set<String> INTERNAL_LLM_AGENTS = Set.of("planning", "executor");

    private LegacyLedgerReplayCompatibility() {
    }

    static boolean isDeepEntry(String entryAgent) {
        return ENTRY_PLAN_SOLVE.equals(entryAgent);
    }

    static boolean isStandardEntry(String entryAgent) {
        return ENTRY_REACT.equals(entryAgent);
    }

    static boolean isDirectAnswerAgent(String agentName) {
        return LLM_WORKFLOW.equals(agentName);
    }

    static boolean isAgentLoopLikeAgent(String agentName) {
        return LLM_REACT.equals(agentName);
    }

    static boolean isInternalAgent(String agentName) {
        return INTERNAL_LLM_AGENTS.contains(agentName);
    }
}
