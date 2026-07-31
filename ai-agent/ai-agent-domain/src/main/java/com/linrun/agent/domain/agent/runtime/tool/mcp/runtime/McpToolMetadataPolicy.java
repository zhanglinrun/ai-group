package com.linrun.agent.domain.agent.runtime.tool.mcp.runtime;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Treats remotely supplied MCP tool metadata as untrusted input.  A tool's
 * callable name and schemas are validated elsewhere; this policy prevents an
 * instruction-like description from being promoted into the model prompt.
 */
public final class McpToolMetadataPolicy {

    private static final int MAX_DESCRIPTION_CHARS = 600;
    private static final String WITHHELD_DESCRIPTION =
            "Remote MCP metadata withheld because it contains instruction-like content.";
    private static final List<String> INSTRUCTION_MARKERS = List.of(
            "ignore previous", "ignore all", "disregard", "override", "system prompt",
            "developer message", "developer instruction", "reveal", "exfiltrate",
            "忽略前", "忽略指令", "系统提示", "开发者指令", "泄露", "外传");

    public boolean isSafeToolName(String name) {
        return StringUtils.isNotBlank(name) && name.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}");
    }

    public String sanitizeDescription(String description, String title) {
        String candidate = StringUtils.defaultIfBlank(description, title);
        String normalized = candidate.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return "Remote MCP tool metadata unavailable.";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (INSTRUCTION_MARKERS.stream().anyMatch(lower::contains)) {
            return WITHHELD_DESCRIPTION;
        }
        if (normalized.length() > MAX_DESCRIPTION_CHARS) {
            normalized = normalized.substring(0, MAX_DESCRIPTION_CHARS) + "…";
        }
        return "[Remote MCP metadata] " + normalized;
    }
}
