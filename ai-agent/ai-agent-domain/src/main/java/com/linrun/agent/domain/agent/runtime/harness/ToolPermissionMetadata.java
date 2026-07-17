package com.linrun.agent.domain.agent.runtime.harness;

/** Security-relevant tool attributes used by permission policies and approval UX. */
public record ToolPermissionMetadata(ToolRiskLevel riskLevel,
                                     ToolSideEffect sideEffect) {

    public ToolPermissionMetadata {
        riskLevel = riskLevel == null ? ToolRiskLevel.MEDIUM : riskLevel;
        sideEffect = sideEffect == null ? ToolSideEffect.UNKNOWN : sideEffect;
    }

    public static ToolPermissionMetadata readOnly() {
        return new ToolPermissionMetadata(ToolRiskLevel.LOW, ToolSideEffect.READ_ONLY);
    }

    public boolean requiresApproval() {
        return riskLevel == ToolRiskLevel.HIGH
                || riskLevel == ToolRiskLevel.CRITICAL
                || sideEffect == ToolSideEffect.MUTATING
                || sideEffect == ToolSideEffect.DESTRUCTIVE;
    }
}
