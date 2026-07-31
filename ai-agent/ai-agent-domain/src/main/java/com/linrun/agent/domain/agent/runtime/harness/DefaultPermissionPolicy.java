package com.linrun.agent.domain.agent.runtime.harness;

import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default stateless non-interactive policy: a tool must be in the active turn view and
 * remote/network capabilities are denied when the request explicitly disables online access.
 * The production factory uses this only when the application has not supplied a policy bean.
 */
public final class DefaultPermissionPolicy implements PermissionPolicy {

    private static final Set<String> PLATFORM_IDENTITY_FIELDS = Set.of(
            "userid", "ownerid", "x-user-id", "xuserid");

    @Override
    public PermissionDecision evaluate(String toolName,
                                       Object input,
                                       ToolCollection activeTools,
                                       AgentContext context) {
        if (StringUtils.isBlank(toolName)) {
            return PermissionDecision.deny("Tool name is missing.");
        }
        if (activeTools == null
                || (!activeTools.getToolMap().containsKey(toolName)
                && !activeTools.getMcpToolMap().containsKey(toolName))) {
            return PermissionDecision.deny(
                    "Tool " + toolName + " is not exposed or allowed for the current turn.");
        }
        ToolInvocationContract invocationContract = context == null
                ? null
                : context.getToolInvocationContract();
        if (invocationContract != null && !invocationContract.allows(toolName)) {
            return PermissionDecision.deny(
                    "Tool " + toolName + " is forbidden by the user tool invocation contract.");
        }
        if (PlatformContextTool.NAME.equals(toolName)) {
            if (context == null || context.getOwnerId() == null || context.getOwnerId() <= 0L) {
                return PermissionDecision.deny(
                        "Tool platform_context requires an authenticated AgentContext identity.");
            }
            if (containsModelControlledIdentity(input)) {
                return PermissionDecision.deny(
                        "Tool platform_context identity must come from AgentContext, not tool input.");
            }
        }
        if (context != null && Boolean.FALSE.equals(context.getOnline())) {
            boolean mcpToolPresent = activeTools.getMcpToolMap().containsKey(toolName);
            McpToolInfo mcpTool = activeTools.getMcpToolMap().get(toolName);
            if ((mcpToolPresent && (mcpTool == null || !mcpTool.isOfflineEligible()))
                    || "deep_search".equals(toolName)
                    || "web_fetch".equals(toolName)) {
                return PermissionDecision.deny(
                        "Online tool " + toolName + " is disabled for this request.");
            }
        }
        ToolPermissionMetadata metadata = resolveMetadata(toolName, activeTools);
        if (metadata.requiresApproval()) {
            return PermissionDecision.ask(
                    "Tool " + toolName + " requires user approval before execution.", metadata);
        }
        return PermissionDecision.allow(metadata);
    }

    private ToolPermissionMetadata resolveMetadata(String toolName, ToolCollection activeTools) {
        if (activeTools.getTool(toolName) != null) {
            return activeTools.getTool(toolName).permissionMetadata();
        }
        McpToolInfo mcpTool = activeTools.getMcpTool(toolName);
        if (mcpTool != null) {
            if (mcpTool.getOrigin() == McpToolOrigin.USER_EXTENSION) {
                // A remote endpoint registered by a user cannot self-attest that it is read-only.
                return new ToolPermissionMetadata(ToolRiskLevel.HIGH, ToolSideEffect.UNKNOWN);
            }
            return new ToolPermissionMetadata(mcpTool.getRiskLevel(), mcpTool.getSideEffect());
        }
        return ToolPermissionMetadata.readOnly();
    }

    private boolean containsModelControlledIdentity(Object input) {
        if (!(input instanceof Map<?, ?> params)) {
            return false;
        }
        for (Object rawKey : params.keySet()) {
            if (rawKey == null) {
                continue;
            }
            String normalized = String.valueOf(rawKey)
                    .replace("_", "")
                    .toLowerCase(Locale.ROOT);
            if (PLATFORM_IDENTITY_FIELDS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }
}
