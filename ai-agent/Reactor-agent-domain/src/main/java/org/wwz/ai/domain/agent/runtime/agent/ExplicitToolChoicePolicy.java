package org.wwz.ai.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolChoice;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Honors an explicit user request to use a tool without forcing every ReAct
 * round to call one. Later rounds remain AUTO so the model can finish.
 */
public final class ExplicitToolChoicePolicy {

    private static final List<String> NEGATIVE_DIRECTIVES = List.of(
            "不调用", "不要调用", "无需调用", "不使用工具", "无需使用任何工具",
            "do not use", "don't use", "without using"
    );
    private static final List<String> EXPLICIT_DIRECTIVES = List.of(
            "必须调用", "务必调用", "请调用", "必须使用", "务必使用", "请使用"
    );
    private static final List<String> EXPLICIT_NETWORK_DIRECTIVES = List.of(
            "请联网搜索", "请联网检索", "请联网查证", "联网搜索", "联网检索", "联网查证",
            "在线搜索", "在线检索", "在线查证"
    );
    private static final List<String> NETWORK_LOOKUP_ACTIONS = List.of("查阅", "搜索", "检索", "抓取", "访问");
    private static final List<String> NETWORK_SOURCES = List.of(
            "官方文档", "官网", "github", "网页", "http://", "https://"
    );
    private static final List<String> SINGLE_USE_DIRECTIVES = List.of(
            "一次", "1次", "1 次", "one time", "once", "exactly one"
    );

    private ExplicitToolChoicePolicy() {
    }

    public static ToolChoice resolve(String query, int currentStep) {
        if (currentStep > 1 || StringUtils.isBlank(query)) {
            return ToolChoice.AUTO;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        if (prohibitsToolUse(normalized)) {
            return ToolChoice.AUTO;
        }

        boolean namesTool = normalized.contains("工具") || normalized.contains(" tool") || normalized.contains("_tool");
        boolean explicitToolRequest = namesTool && containsAny(normalized, EXPLICIT_DIRECTIVES);
        boolean explicitSkillLoad = normalized.contains("skill")
                && (normalized.contains("加载") || normalized.contains("load "));
        boolean explicitEnglishRequest = namesTool && (normalized.contains("must use")
                || normalized.contains("please use the")
                || normalized.contains("please call the"));
        boolean explicitNetworkRequest = containsAny(normalized, EXPLICIT_NETWORK_DIRECTIVES);
        boolean explicitSourceLookup = containsAny(normalized, NETWORK_LOOKUP_ACTIONS)
                && containsAny(normalized, NETWORK_SOURCES);
        return explicitToolRequest || explicitSkillLoad || explicitEnglishRequest
                || explicitNetworkRequest || explicitSourceLookup
                ? ToolChoice.REQUIRED
                : ToolChoice.AUTO;
    }

    public static boolean prohibitsToolUse(String query) {
        if (StringUtils.isBlank(query)) {
            return false;
        }
        return containsAny(query.toLowerCase(Locale.ROOT), NEGATIVE_DIRECTIVES);
    }

    public static ToolChoice resolveForCurrentTask(String originalQuery,
                                                   String currentTask,
                                                   int currentStep) {
        if (prohibitsToolUse(StringUtils.defaultString(originalQuery) + "\n"
                + StringUtils.defaultString(currentTask))) {
            return ToolChoice.AUTO;
        }
        String scopedQuery = StringUtils.isNotBlank(currentTask) ? currentTask : originalQuery;
        ToolChoice resolved = resolve(scopedQuery, currentStep);
        return resolved == ToolChoice.AUTO && currentStep <= 1 && isImperativePlanToolTask(currentTask)
                ? ToolChoice.REQUIRED
                : resolved;
    }

    /**
     * Resolves one explicitly requested tool to its canonical available name.
     * Ambiguous, unavailable, embedded, and prohibited names remain unforced.
     */
    public static String resolveRequiredToolName(String query,
                                                 int currentStep,
                                                 Collection<String> availableToolNames) {
        if (resolve(query, currentStep) != ToolChoice.REQUIRED
                || availableToolNames == null || availableToolNames.isEmpty()) {
            return null;
        }
        return matchAvailableToolName(query, availableToolNames);
    }

    private static String matchAvailableToolName(String query, Collection<String> availableToolNames) {
        String normalizedQuery = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        String matched = null;
        for (String toolName : availableToolNames) {
            if (StringUtils.isBlank(toolName)
                    || !containsIdentifier(normalizedQuery, toolName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (matched != null && !matched.equals(toolName)) {
                return null;
            }
            matched = toolName;
        }
        return matched;
    }

    public static String resolveRequiredToolNameForCurrentTask(String originalQuery,
                                                               String currentTask,
                                                               int currentStep,
                                                               Collection<String> availableToolNames) {
        if (prohibitsToolUse(StringUtils.defaultString(originalQuery) + "\n"
                + StringUtils.defaultString(currentTask))) {
            return null;
        }
        String scopedQuery = StringUtils.isNotBlank(currentTask) ? currentTask : originalQuery;
        return resolveForCurrentTask(originalQuery, currentTask, currentStep) == ToolChoice.REQUIRED
                && availableToolNames != null && !availableToolNames.isEmpty()
                ? matchAvailableToolName(scopedQuery, availableToolNames)
                : null;
    }

    /**
     * Resolves an explicitly named tool whose user request also limits it to one call in the run.
     */
    public static String resolveSingleUseRequiredToolName(String query,
                                                          Collection<String> availableToolNames) {
        String normalized = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        int constraintEnd = resolveLastDirectiveEnd(normalized, SINGLE_USE_DIRECTIVES);
        if (constraintEnd < 0) {
            return null;
        }
        // Output-style instructions are appended after the user's original query and may name
        // another delivery tool (for example report_tool). Only the text up to the single-use
        // constraint belongs to that budget; later system-appended tool names must not make it
        // look ambiguous and silently disable the run-level guard.
        return resolveRequiredToolName(normalized.substring(0, constraintEnd), 1, availableToolNames);
    }

    private static int resolveLastDirectiveEnd(String value, List<String> directives) {
        int lastEnd = -1;
        for (String directive : directives) {
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

    private static boolean isImperativePlanToolTask(String currentTask) {
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

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }
}
