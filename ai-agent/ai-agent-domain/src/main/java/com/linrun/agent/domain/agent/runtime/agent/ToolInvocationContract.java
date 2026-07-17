package com.linrun.agent.domain.agent.runtime.agent;

import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Canonical run-level contract for model-visible and executable tools.
 * todo_write remains available as Harness control-plane infrastructure.
 */
public record ToolInvocationContract(Set<String> requiredToolNames,
                                     Set<String> allowedToolNames,
                                     Set<String> forbiddenToolNames,
                                     boolean exclusive) {

    private static final Set<String> HARNESS_CONTROL_TOOLS = Set.of(TodoWriteTool.NAME);
    private static final ToolInvocationContract NONE = new ToolInvocationContract(
            Set.of(), Set.of(), Set.of(), false);

    public ToolInvocationContract {
        requiredToolNames = immutableNames(requiredToolNames);
        allowedToolNames = immutableNames(allowedToolNames);
        forbiddenToolNames = immutableNames(forbiddenToolNames);
    }

    public static ToolInvocationContract none() {
        return NONE;
    }

    public static ToolInvocationContract resolve(String query,
                                                 Collection<String> activeToolNames) {
        Set<String> catalogNames = immutableNames(activeToolNames);
        ExplicitToolChoicePolicy.ExplicitToolRequirement requirement =
                ExplicitToolChoicePolicy.inspectRequiredTool(query, 1, catalogNames);

        Set<String> required = new LinkedHashSet<>();
        if (requirement.resolution() == ExplicitToolChoicePolicy.RequirementResolution.RESOLVED) {
            required.add(requirement.canonicalToolName());
        }

        Set<String> forbidden = new LinkedHashSet<>();
        for (String rawName : ToolDirectiveParser.extractNegatedToolIdentifiers(query)) {
            String canonicalName = ToolRequirementResolver.resolveCanonicalToolName(
                    rawName, catalogNames);
            if (canonicalName != null) {
                forbidden.add(canonicalName);
            }
        }

        boolean exclusive = !required.isEmpty()
                && ToolDirectiveParser.requiresExclusiveToolUse(query);
        if (required.isEmpty() && forbidden.isEmpty() && !exclusive) {
            return none();
        }

        Set<String> allowed = new LinkedHashSet<>(catalogNames);
        allowed.removeAll(forbidden);
        if (exclusive) {
            allowed.retainAll(required);
        }
        return new ToolInvocationContract(required, allowed, forbidden, exclusive);
    }

    public boolean constrained() {
        return exclusive || !requiredToolNames.isEmpty() || !forbiddenToolNames.isEmpty();
    }

    public boolean allows(String canonicalToolName) {
        if (canonicalToolName == null || canonicalToolName.isBlank()) {
            return false;
        }
        if (HARNESS_CONTROL_TOOLS.contains(canonicalToolName)) {
            return true;
        }
        if (forbiddenToolNames.contains(canonicalToolName)) {
            return false;
        }
        return !exclusive || allowedToolNames.contains(canonicalToolName);
    }

    public boolean isHarnessControlTool(String canonicalToolName) {
        return HARNESS_CONTROL_TOOLS.contains(canonicalToolName);
    }

    private static Set<String> immutableNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(name);
            }
        }
        return Set.copyOf(normalized);
    }
}
