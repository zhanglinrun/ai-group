package com.linrun.agent.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses user-level tool directives without consulting the active catalog. */
final class ToolDirectiveParser {

    private static final List<String> SINGLE_USE_DIRECTIVES = List.of(
            "一次", "1次", "1 次", "one time", "once", "exactly one"
    );
    private static final List<String> NEGATED_REQUIREMENT_MARKERS = List.of(
            "不是", "并非", "无需", "不必", "不要", "禁止", "不得", "不要求",
            "not required", "not necessary", "do not", "don't", "must not", "without"
    );
    private static final Pattern POSITIVE_TOOL_DIRECTIVE = Pattern.compile(
            "(?:必须|务必|只能|请)(?:(?![。！？!?；;\\n]).){0,48}?(?:调用|使用)"
                    + "|\\b(?:must|please|only)\\b(?:(?![.!?;\\n]).){0,40}?\\b(?:call|use)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOOL_ACTION = Pattern.compile(
            "(?:调用|使用)|\\b(?:call|use)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXCLUSIVE_TOOL_DIRECTIVE = Pattern.compile(
            "(?:只能|仅能|只允许)(?:(?![。！？!?；;\\n]).){0,32}?(?:调用|使用)"
                    + "|(?:禁止|不得|不要)(?:(?![。！？!?；;\\n]).){0,128}?(?:任何|任意)(?:其他|替代)?工具"
                    + "|\\b(?:only|must\\s+only)\\s+(?:call|use)\\b"
                    + "|\\bwithout\\s+any\\s+(?:other|alternative)\\s+tools?\\b"
                    + "|\\bno\\s+(?:other|alternative)\\s+tools?\\b"
                    + "|\\b(?:do not|don't|must not)\\s+use\\s+any\\s+(?:other|alternative)\\s+tools?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOOL_IDENTIFIER = Pattern.compile(
            "(?<![a-z0-9_-])(?:mcp__[a-z0-9_-]+__[a-z0-9_-]+|[a-z][a-z0-9-]*(?:_[a-z0-9-]+)+)(?![a-z0-9_-])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern QUOTED_TOOL_IDENTIFIER = Pattern.compile(
            "[`'\"“‘]([a-z][a-z0-9_-]{1,127})[`'\"”’]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LABELED_TOOL_IDENTIFIER = Pattern.compile(
            "(?<![a-z0-9_-])(?:mcp\\s*)?(?:工具|tool)\\s*[`'\"“‘]?([a-z][a-z0-9_-]{1,127})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TARGET_BOUNDARY = Pattern.compile("[，,。！？!?；;：:\\n]");
    private static final Pattern ALL_TOOL_PROHIBITION = Pattern.compile(
            "(?:不|不要|无需|不必|禁止|不得)(?:调用|使用)(?:任何|任意|所有)?工具"
                    + "(?=\\s*(?:$|[，,。！？!?；;]|直接|完成|回答))"
                    + "|\\b(?:do not|don't|must not) use (?:any |all )?tools?\\b"
                    + "|\\bwithout using (?:any )?tools?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ToolDirectiveParser() {
    }

    static boolean prohibitsAllToolUse(String query) {
        return StringUtils.isNotBlank(query)
                && ALL_TOOL_PROHIBITION.matcher(query.toLowerCase(Locale.ROOT)).find();
    }

    static boolean hasPositiveToolDirective(String value) {
        Matcher matcher = POSITIVE_TOOL_DIRECTIVE.matcher(StringUtils.defaultString(value));
        while (matcher.find()) {
            if (!isNegatedRequirement(value, matcher.start(), matcher.group())) {
                return true;
            }
        }
        return false;
    }

    static boolean explicitlyNamesTool(String normalizedQuery) {
        String query = StringUtils.defaultString(normalizedQuery);
        return query.contains("工具") || query.contains(" tool") || query.contains("_tool")
                || !extractExplicitToolIdentifiers(query, false).isEmpty();
    }

    static boolean explicitlyLoadsSkill(String normalizedQuery) {
        String query = StringUtils.defaultString(normalizedQuery);
        return query.contains("skill") && (query.contains("加载") || query.contains("load "));
    }

    static boolean requiresExclusiveToolUse(String query) {
        return StringUtils.isNotBlank(query)
                && EXCLUSIVE_TOOL_DIRECTIVE.matcher(query.toLowerCase(Locale.ROOT)).find();
    }

    static boolean isImperativePlanToolTask(String currentTask) {
        if (StringUtils.isBlank(currentTask)) {
            return false;
        }
        String normalized = currentTask.trim().toLowerCase(Locale.ROOT);
        boolean namesTool = normalized.contains("工具") || normalized.contains(" tool")
                || normalized.contains("_tool");
        return namesTool && (normalized.startsWith("调用")
                || normalized.contains("任务是：调用")
                || normalized.contains("任务是:调用")
                || normalized.startsWith("call ")
                || normalized.contains("task is to call "));
    }

    static boolean explicitlyProhibitsCurrentTaskTool(String originalQuery,
                                                       String currentTask) {
        if (StringUtils.isBlank(originalQuery) || StringUtils.isBlank(currentTask)) {
            return false;
        }
        Set<String> prohibitedNames = extractNegatedToolIdentifiers(originalQuery);
        if (prohibitedNames.isEmpty()) {
            return false;
        }
        Set<String> taskNames = new LinkedHashSet<>(extractExplicitToolIdentifiers(currentTask, true));
        taskNames.retainAll(prohibitedNames);
        return !taskNames.isEmpty();
    }

    static List<String> extractExplicitToolIdentifiers(String query,
                                                       boolean allowImperativeAction) {
        Set<String> identifiers = new LinkedHashSet<>();
        for (String target : extractToolTargetSegments(query, allowImperativeAction)) {
            collectIdentifiers(target, TOOL_IDENTIFIER, 0, identifiers);
            collectIdentifiers(target, QUOTED_TOOL_IDENTIFIER, 1, identifiers);
            collectIdentifiers(target, LABELED_TOOL_IDENTIFIER, 1, identifiers);
        }
        return List.copyOf(identifiers);
    }

    static List<String> extractToolTargetSegments(String query,
                                                  boolean allowImperativeAction) {
        String normalized = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        List<Integer> actionEnds = new ArrayList<>();
        Matcher directiveMatcher = POSITIVE_TOOL_DIRECTIVE.matcher(normalized);
        while (directiveMatcher.find()) {
            if (!isNegatedRequirement(normalized, directiveMatcher.start(), directiveMatcher.group())) {
                actionEnds.add(directiveMatcher.end());
            }
        }
        if (actionEnds.isEmpty() && allowImperativeAction) {
            Matcher actionMatcher = TOOL_ACTION.matcher(normalized);
            while (actionMatcher.find()) {
                if (!isNegatedRequirement(normalized, actionMatcher.start(), actionMatcher.group())) {
                    actionEnds.add(actionMatcher.end());
                }
            }
        }

        List<String> targets = new ArrayList<>();
        for (Integer actionEnd : actionEnds) {
            if (actionEnd == null || actionEnd >= normalized.length()) {
                continue;
            }
            String tail = normalized.substring(actionEnd).stripLeading();
            Matcher boundary = TARGET_BOUNDARY.matcher(tail);
            String target = boundary.find() ? tail.substring(0, boundary.start()) : tail;
            if (!target.isBlank()) {
                targets.add(target);
            }
        }
        return targets;
    }

    static int resolveSingleUseDirectiveEnd(String value) {
        int lastEnd = -1;
        for (String directive : SINGLE_USE_DIRECTIVES) {
            int index = value.lastIndexOf(directive);
            if (index < 0) {
                continue;
            }
            int end = index + directive.length();
            if (end > lastEnd) {
                lastEnd = end;
            }
        }
        return lastEnd;
    }

    private static boolean isNegatedRequirement(String value, int directiveStart, String matchedText) {
        String matched = StringUtils.defaultString(matchedText).toLowerCase(Locale.ROOT);
        if (containsAny(matched, NEGATED_REQUIREMENT_MARKERS)) {
            return true;
        }
        int prefixStart = Math.max(0, directiveStart - 12);
        String prefix = StringUtils.defaultString(value)
                .substring(prefixStart, directiveStart)
                .toLowerCase(Locale.ROOT);
        int hardBoundary = lastBoundaryIndex(prefix);
        String localPrefix = hardBoundary < 0 ? prefix : prefix.substring(hardBoundary + 1);
        return containsAny(localPrefix, NEGATED_REQUIREMENT_MARKERS);
    }

    private static int lastBoundaryIndex(String value) {
        int boundary = -1;
        for (char marker : new char[]{'。', '！', '？', ';', '；', '\n', ',', '，'}) {
            boundary = Math.max(boundary, value.lastIndexOf(marker));
        }
        boundary = Math.max(boundary, value.lastIndexOf("但是"));
        boundary = Math.max(boundary, value.lastIndexOf("但"));
        boundary = Math.max(boundary, value.lastIndexOf("而是"));
        return boundary;
    }

    static Set<String> extractNegatedToolIdentifiers(String query) {
        String normalized = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        Set<String> identifiers = new LinkedHashSet<>();
        for (String marker : List.of(
                "不要调用", "无需调用", "不必调用", "禁止调用", "不得调用",
                "不要使用", "无需使用", "不必使用", "禁止使用", "不得使用",
                "do not use", "don't use", "must not use")) {
            int offset = normalized.indexOf(marker);
            while (offset >= 0) {
                String tail = normalized.substring(offset + marker.length()).stripLeading();
                Matcher boundary = TARGET_BOUNDARY.matcher(tail);
                String target = boundary.find() ? tail.substring(0, boundary.start()) : tail;
                collectIdentifiers(target, TOOL_IDENTIFIER, 0, identifiers);
                collectIdentifiers(target, QUOTED_TOOL_IDENTIFIER, 1, identifiers);
                collectIdentifiers(target, LABELED_TOOL_IDENTIFIER, 1, identifiers);
                offset = normalized.indexOf(marker, offset + marker.length());
            }
        }
        return identifiers;
    }

    private static void collectIdentifiers(String value,
                                           Pattern pattern,
                                           int group,
                                           Set<String> identifiers) {
        Matcher matcher = pattern.matcher(StringUtils.defaultString(value));
        while (matcher.find()) {
            String identifier = matcher.group(group).toLowerCase(Locale.ROOT);
            if (!identifier.equals("mcp") && !identifier.equals("tool")) {
                identifiers.add(identifier);
            }
        }
    }

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }
}
