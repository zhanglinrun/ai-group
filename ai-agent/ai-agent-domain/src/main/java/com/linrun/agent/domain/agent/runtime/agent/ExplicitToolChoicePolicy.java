package com.linrun.agent.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility facade for explicit tool choice, catalog resolution, and
 * network-capability policies used by the Agent Loop.
 */
public final class ExplicitToolChoicePolicy {

    private ExplicitToolChoicePolicy() {
    }

    public static ToolChoice resolve(String query, int currentStep) {
        if (StringUtils.isBlank(query)) {
            return ToolChoice.AUTO;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        if (prohibitsToolUse(normalized)) {
            return ToolChoice.NONE;
        }
        if (currentStep > 1) {
            return ToolChoice.AUTO;
        }

        boolean explicitToolRequest = ToolDirectiveParser.hasPositiveToolDirective(normalized)
                && ToolDirectiveParser.explicitlyNamesTool(normalized);
        if (explicitToolRequest || ToolDirectiveParser.explicitlyLoadsSkill(normalized)) {
            return ToolChoice.REQUIRED;
        }
        return requiresNetworkLookup(normalized) ? ToolChoice.REQUIRED : ToolChoice.AUTO;
    }

    /** Whether the user explicitly requires fresh external lookup rather than model-only knowledge. */
    public static boolean requiresNetworkLookup(String query) {
        return NetworkCapabilityPolicy.requiresNetworkLookup(query);
    }

    /** Conservative capability mapping used by fail-fast checks and typed evidence validation. */
    public static boolean isNetworkLookupToolName(String toolName) {
        return NetworkCapabilityPolicy.isNetworkLookupToolName(toolName);
    }

    public static boolean hasNetworkLookupTool(Collection<String> availableToolNames) {
        return NetworkCapabilityPolicy.hasNetworkLookupTool(availableToolNames);
    }

    public static boolean prohibitsToolUse(String query) {
        return ToolDirectiveParser.prohibitsAllToolUse(query);
    }

    public static ToolChoice resolveForCurrentTask(String originalQuery,
                                                   String currentTask,
                                                   int currentStep) {
        String combined = StringUtils.defaultString(originalQuery) + "\n"
                + StringUtils.defaultString(currentTask);
        if (prohibitsToolUse(combined)
                || ToolDirectiveParser.explicitlyProhibitsCurrentTaskTool(originalQuery, currentTask)) {
            return ToolChoice.NONE;
        }
        String scopedQuery = StringUtils.isNotBlank(currentTask) ? currentTask : originalQuery;
        ToolChoice resolved = resolve(scopedQuery, currentStep);
        return resolved == ToolChoice.AUTO && currentStep <= 1
                && ToolDirectiveParser.isImperativePlanToolTask(currentTask)
                ? ToolChoice.REQUIRED
                : resolved;
    }

    /** Resolves one explicitly requested tool to its canonical active-catalog name. */
    public static String resolveRequiredToolName(String query,
                                                 int currentStep,
                                                 Collection<String> availableToolNames) {
        ExplicitToolRequirement requirement = inspectRequiredTool(
                query, currentStep, availableToolNames);
        return requirement.resolution() == RequirementResolution.RESOLVED
                ? requirement.canonicalToolName()
                : null;
    }

    /** Retains unavailable or ambiguous named requirements for fail-fast handling. */
    public static ExplicitToolRequirement inspectRequiredTool(String query,
                                                               int currentStep,
                                                               Collection<String> availableToolNames) {
        if (resolve(query, currentStep) != ToolChoice.REQUIRED) {
            return ExplicitToolRequirement.none();
        }
        return ToolRequirementResolver.inspect(query, availableToolNames, false);
    }

    public static String resolveRequiredToolNameForCurrentTask(String originalQuery,
                                                               String currentTask,
                                                               int currentStep,
                                                               Collection<String> availableToolNames) {
        String combined = StringUtils.defaultString(originalQuery) + "\n"
                + StringUtils.defaultString(currentTask);
        if (prohibitsToolUse(combined)
                || ToolDirectiveParser.explicitlyProhibitsCurrentTaskTool(originalQuery, currentTask)) {
            return null;
        }
        String scopedQuery = StringUtils.isNotBlank(currentTask) ? currentTask : originalQuery;
        if (resolveForCurrentTask(originalQuery, currentTask, currentStep) != ToolChoice.REQUIRED) {
            return null;
        }
        ExplicitToolRequirement requirement = ToolRequirementResolver.inspect(
                scopedQuery, availableToolNames, StringUtils.isNotBlank(currentTask));
        return requirement.resolution() == RequirementResolution.RESOLVED
                ? requirement.canonicalToolName()
                : null;
    }

    /** Resolves an explicitly named tool whose user request limits it to one run-level call. */
    public static String resolveSingleUseRequiredToolName(String query,
                                                          Collection<String> availableToolNames) {
        String normalized = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        int constraintEnd = ToolDirectiveParser.resolveSingleUseDirectiveEnd(normalized);
        if (constraintEnd < 0) {
            return null;
        }
        // Ignore output-style instructions appended after the user's single-use constraint.
        return resolveRequiredToolName(normalized.substring(0, constraintEnd), 1, availableToolNames);
    }

    /**
     * Resolves every explicitly named, unambiguous tool in a positive user
     * directive. Callers that require exactly one target should keep using
     * {@link #inspectRequiredTool(String, int, Collection)}; graph adapters
     * can use this method to retain one safe capability from a request that
     * also names independent local output tools.
     */
    public static List<String> resolveExplicitToolNames(String query,
                                                        int currentStep,
                                                        Collection<String> availableToolNames) {
        if (resolve(query, currentStep) != ToolChoice.REQUIRED) {
            return List.of();
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String requestedName : ToolDirectiveParser.extractExplicitToolIdentifiers(query, false)) {
            String canonicalName = ToolRequirementResolver.resolveCanonicalToolName(
                    requestedName, availableToolNames);
            if (canonicalName != null) {
                resolved.add(canonicalName);
            }
        }
        return List.copyOf(resolved);
    }

    public enum RequirementResolution {
        NONE,
        UNSPECIFIED,
        RESOLVED,
        UNAVAILABLE,
        AMBIGUOUS,
        MULTIPLE
    }

    public record ExplicitToolRequirement(RequirementResolution resolution,
                                          String requestedToolName,
                                          String canonicalToolName) {

        private static ExplicitToolRequirement none() {
            return new ExplicitToolRequirement(RequirementResolution.NONE, null, null);
        }

        public boolean shouldFailFast() {
            return resolution == RequirementResolution.UNAVAILABLE
                    || resolution == RequirementResolution.AMBIGUOUS;
        }
    }
}
