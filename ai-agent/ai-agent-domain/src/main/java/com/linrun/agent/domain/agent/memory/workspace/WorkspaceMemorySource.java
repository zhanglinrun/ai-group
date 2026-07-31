package com.linrun.agent.domain.agent.memory.workspace;

/** Explicit user data is durable; curator output remains a suggestion until confirmed by the user. */
public enum WorkspaceMemorySource {
    EXPLICIT_USER,
    CURATOR_SUGGESTION,
    IMPORT
}
