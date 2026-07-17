package com.linrun.agent.domain.agent.runtime.harness;

public enum ToolSideEffect {
    READ_ONLY,
    LOCAL_WRITE,
    MUTATING,
    DESTRUCTIVE,
    UNKNOWN
}
