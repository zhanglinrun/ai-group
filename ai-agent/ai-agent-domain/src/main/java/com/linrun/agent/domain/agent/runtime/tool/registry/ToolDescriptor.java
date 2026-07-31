package com.linrun.agent.domain.agent.runtime.tool.registry;

import com.linrun.agent.domain.agent.runtime.harness.ToolRiskLevel;
import com.linrun.agent.domain.agent.runtime.harness.ToolSideEffect;

/**
 * Versioned, policy-relevant metadata for one model-callable tool.
 * Schemas are stored as canonical JSON strings so the descriptor can be
 * persisted in a trace or compared across model turns without retaining
 * executable objects or credentials.
 */
public record ToolDescriptor(
        String name,
        String version,
        String definitionHash,
        String inputSchema,
        String outputSchema,
        ToolRiskLevel riskLevel,
        ToolSideEffect sideEffect,
        int timeoutSeconds,
        ToolRetryPolicy retryPolicy,
        boolean concurrencySafe,
        boolean approvalRequired,
        String owner,
        boolean enabled,
        ToolSource source) {

    public ToolDescriptor {
        name = name == null ? "" : name;
        version = version == null ? "unknown" : version;
        definitionHash = definitionHash == null ? "" : definitionHash;
        inputSchema = inputSchema == null ? "{}" : inputSchema;
        outputSchema = outputSchema == null ? "{}" : outputSchema;
        riskLevel = riskLevel == null ? ToolRiskLevel.MEDIUM : riskLevel;
        sideEffect = sideEffect == null ? ToolSideEffect.UNKNOWN : sideEffect;
        timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        retryPolicy = retryPolicy == null ? ToolRetryPolicy.NONE : retryPolicy;
        owner = owner == null ? "unknown" : owner;
        source = source == null ? ToolSource.INTERNAL : source;
    }
}
