package com.linrun.agent.domain.agent.runtime.tool.mcp.runtime;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

import java.util.Collections;
import java.util.List;

/**
 * MCP 工具统一执行器。
 * Reactor 侧继续维持原有调用方式，但底层统一走 McpRegistry 复用预热好的客户端和工具缓存。
 */
@Service
public class McpToolExecutor {

    @Resource
    private McpRegistry mcpRegistry;

    /**
     * 获取当前全局启用的 MCP 工具列表。
     */
    public List<McpToolInfo> discoverConfiguredTools() {
        return mcpRegistry.listGlobalEnabledTools();
    }

    /**
     * 只发现离线请求可用的系统配置 STDIO MCP，不触发任何 HTTP/SSE MCP 初始化。
     */
    public List<McpToolInfo> discoverOfflineEligibleConfiguredTools() {
        return mcpRegistry.listOfflineEligibleConfiguredTools();
    }

    /**
     * 兼容保留：根据 mcpId 列表获取工具列表。
     */
    public List<McpToolInfo> discoverTools(List<String> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mcpRegistry.listToolsByMcpIds(mcpIds);
    }

    public List<McpToolInfo> discoverToolsForClients(List<String> clientIds) {
        return mcpRegistry.listToolsByClientIds(clientIds);
    }

    public List<McpToolInfo> discoverOfflineEligibleToolsForClients(List<String> clientIds) {
        return mcpRegistry.listOfflineEligibleToolsByClientIds(clientIds);
    }

    /**
     * 执行单个 MCP 工具。
     */
    public ToolResultPayload executeTool(McpToolInfo toolInfo, Object args) {
        if (toolInfo == null || StringUtils.isBlank(toolInfo.getName())) {
            return ToolResultPayload.failure(
                    "ToolUnknown Error.",
                    "ToolUnknown Error.",
                    null,
                    "Invalid MCP tool metadata"
            );
        }

        String mcpId = StringUtils.defaultIfBlank(toolInfo.getMcpId(), toolInfo.getServerKey());
        return mcpRegistry.executeTool(mcpId, toolInfo.getName(), args);
    }
}
