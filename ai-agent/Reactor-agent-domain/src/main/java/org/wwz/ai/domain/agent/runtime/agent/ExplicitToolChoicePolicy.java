package org.wwz.ai.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolChoice;

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

        boolean namesTool = normalized.contains("工具") || normalized.contains(" tool");
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

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }
}
