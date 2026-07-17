package com.linrun.agent.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Resolves parsed user tool targets against the active catalog. */
final class ToolRequirementResolver {

    private ToolRequirementResolver() {
    }

    static String resolveCanonicalToolName(String requestedName,
                                           Collection<String> availableToolNames) {
        List<String> matches = resolveAvailableMatches(requestedName, availableToolNames);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    static ExplicitToolChoicePolicy.ExplicitToolRequirement inspect(
            String query,
            Collection<String> availableToolNames,
            boolean allowImperativeAction) {
        List<String> requestedNames = ToolDirectiveParser.extractExplicitToolIdentifiers(
                query, allowImperativeAction);
        if (requestedNames.size() > 1) {
            return requirement(
                    ExplicitToolChoicePolicy.RequirementResolution.MULTIPLE,
                    String.join(",", requestedNames),
                    null);
        }
        if (requestedNames.size() == 1) {
            return resolveNamedTarget(requestedNames.get(0), availableToolNames);
        }

        String targetText = String.join("\n", ToolDirectiveParser.extractToolTargetSegments(
                query, allowImperativeAction));
        String matched = matchAvailableToolName(targetText, availableToolNames);
        return matched == null
                ? requirement(ExplicitToolChoicePolicy.RequirementResolution.UNSPECIFIED, null, null)
                : requirement(ExplicitToolChoicePolicy.RequirementResolution.RESOLVED, matched, matched);
    }

    private static ExplicitToolChoicePolicy.ExplicitToolRequirement resolveNamedTarget(
            String requestedName,
            Collection<String> availableToolNames) {
        List<String> matches = resolveAvailableMatches(requestedName, availableToolNames);
        if (matches.isEmpty()) {
            return requirement(
                    ExplicitToolChoicePolicy.RequirementResolution.UNAVAILABLE,
                    requestedName,
                    null);
        }
        if (matches.size() > 1) {
            return requirement(
                    ExplicitToolChoicePolicy.RequirementResolution.AMBIGUOUS,
                    requestedName,
                    null);
        }
        return requirement(
                ExplicitToolChoicePolicy.RequirementResolution.RESOLVED,
                requestedName,
                matches.get(0));
    }

    private static List<String> resolveAvailableMatches(String requestedName,
                                                        Collection<String> availableToolNames) {
        if (StringUtils.isBlank(requestedName)
                || availableToolNames == null || availableToolNames.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String toolName : availableToolNames) {
            if (StringUtils.isBlank(toolName)) {
                continue;
            }
            String normalizedToolName = toolName.toLowerCase(Locale.ROOT);
            if (requestedName.equalsIgnoreCase(toolName)
                    || normalizedToolName.startsWith("mcp__")
                    && requestedName.equalsIgnoreCase(remoteMcpToolName(normalizedToolName))) {
                matches.add(toolName);
            }
        }
        return matches;
    }

    private static String matchAvailableToolName(String query,
                                                 Collection<String> availableToolNames) {
        if (StringUtils.isBlank(query)
                || availableToolNames == null || availableToolNames.isEmpty()) {
            return null;
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String matched = null;
        for (String toolName : availableToolNames) {
            if (StringUtils.isBlank(toolName)
                    || !matchesExposedOrMcpAlias(normalizedQuery, toolName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (matched != null && !matched.equals(toolName)) {
                return null;
            }
            matched = toolName;
        }
        return matched;
    }

    private static boolean matchesExposedOrMcpAlias(String normalizedQuery, String toolName) {
        if (containsIdentifier(normalizedQuery, toolName)) {
            return true;
        }
        return toolName.startsWith("mcp__")
                && containsIdentifier(normalizedQuery, remoteMcpToolName(toolName));
    }

    private static String remoteMcpToolName(String canonicalName) {
        int separator = canonicalName.lastIndexOf("__");
        return separator < 0 ? canonicalName : canonicalName.substring(separator + 2);
    }

    private static boolean containsIdentifier(String value, String identifier) {
        int index = value.indexOf(identifier);
        while (index >= 0) {
            int end = index + identifier.length();
            boolean leftBoundary = index == 0 || !isIdentifierCharacter(value.charAt(index - 1));
            boolean rightBoundary = end == value.length() || !isIdentifierCharacter(value.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            index = value.indexOf(identifier, index + 1);
        }
        return false;
    }

    private static boolean isIdentifierCharacter(char value) {
        return value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '_' || value == '-';
    }

    private static ExplicitToolChoicePolicy.ExplicitToolRequirement requirement(
            ExplicitToolChoicePolicy.RequirementResolution resolution,
            String requestedToolName,
            String canonicalToolName) {
        return new ExplicitToolChoicePolicy.ExplicitToolRequirement(
                resolution, requestedToolName, canonicalToolName);
    }
}
