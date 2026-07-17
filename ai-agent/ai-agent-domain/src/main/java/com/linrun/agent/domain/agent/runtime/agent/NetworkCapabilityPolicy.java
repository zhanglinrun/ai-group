package com.linrun.agent.domain.agent.runtime.agent;

import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Classifies explicit network requirements and network-capable tool names. */
final class NetworkCapabilityPolicy {

    private static final List<String> EXPLICIT_NETWORK_DIRECTIVES = List.of(
            "请联网搜索", "请联网检索", "请联网查证", "联网搜索", "联网检索", "联网查证",
            "在线搜索", "在线检索", "在线查证"
    );
    private static final List<String> NEGATIVE_NETWORK_DIRECTIVES = List.of(
            "不要联网", "无需联网", "不联网", "离线完成", "offline only", "without internet"
    );
    private static final List<String> NETWORK_LOOKUP_ACTIONS = List.of("查阅", "搜索", "检索", "抓取", "访问");
    private static final List<String> NETWORK_SOURCES = List.of(
            "官方文档", "官网", "github", "网页", "http://", "https://"
    );

    private NetworkCapabilityPolicy() {
    }

    static boolean requiresNetworkLookup(String query) {
        if (StringUtils.isBlank(query)) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, NEGATIVE_NETWORK_DIRECTIVES)) {
            return false;
        }
        return containsAny(normalized, EXPLICIT_NETWORK_DIRECTIVES)
                || containsAny(normalized, NETWORK_LOOKUP_ACTIONS)
                && containsAny(normalized, NETWORK_SOURCES);
    }

    static boolean isNetworkLookupToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("tool_search".equals(normalized)) {
            return false;
        }
        if (normalized.equals("search")
                || normalized.equals("deep_search")
                || normalized.equals("web_search")
                || normalized.equals("web_fetch")) {
            return true;
        }
        if (!normalized.startsWith("mcp__")) {
            return false;
        }
        int separator = normalized.lastIndexOf("__");
        String remoteAction = separator < 0 ? normalized : normalized.substring(separator + 2);
        if (containsActionToken(remoteAction,
                List.of("create", "update", "delete", "write", "post", "send", "merge", "close"))) {
            return false;
        }
        return containsActionToken(remoteAction, List.of(
                "search", "fetch", "browse", "read", "get", "list", "query", "find",
                "lookup", "download", "open", "navigate"
        ));
    }

    static boolean hasNetworkLookupTool(Collection<String> availableToolNames) {
        return availableToolNames != null && availableToolNames.stream()
                .anyMatch(NetworkCapabilityPolicy::isNetworkLookupToolName);
    }

    private static boolean containsActionToken(String action, List<String> tokens) {
        return tokens.stream().anyMatch(token -> action.equals(token)
                || action.startsWith(token + "_")
                || action.endsWith("_" + token)
                || action.contains("_" + token + "_"));
    }

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }
}
