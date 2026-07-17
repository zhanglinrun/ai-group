package com.linrun.agent.domain.agent.runtime.dto.tool;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import com.linrun.agent.domain.agent.runtime.harness.ToolRiskLevel;
import com.linrun.agent.domain.agent.runtime.harness.ToolSideEffect;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolInfo {

    /**
     * MCP 配置主键业务标识。
     */
    private String mcpId;

    /**
     * MCP 工具名称。
     */
    private String name;

    /**
     * 暴露给模型的全局唯一名称。真实 MCP 工具使用 mcp__server__tool，
     * name 继续保存远端 tools/call 所需的原始名称。
     */
    private String exposedName;

    /**
     * MCP 工具描述，供提示词和原生 function call 使用。
     */
    private String desc;

    /**
     * MCP 工具参数 Schema，沿用 JSON 字符串格式以兼容现有链路。
     */
    private String parameters;

    /**
     * 传输协议类型，支持 sse/stdio/streamable_http。
     */
    private String transportType;

    /**
     * MCP 配置来源。未知来源在离线模式下不会被信任。
     */
    @Builder.Default
    private McpToolOrigin origin = McpToolOrigin.UNKNOWN;

    /**
     * Opt out of run-local successful-operation reuse for polling or other
     * time-sensitive MCP tools. Identical successful calls are reused by default.
     */
    @Builder.Default
    private boolean allowRepeatedSuccessfulCall = false;

    /** Server/admin supplied risk metadata; absent MCP metadata defaults to a remote read. */
    @Builder.Default
    private ToolRiskLevel riskLevel = ToolRiskLevel.MEDIUM;

    @Builder.Default
    private ToolSideEffect sideEffect = ToolSideEffect.READ_ONLY;

    /**
     * 服务唯一标识，默认与 serverUrl 相同。
     */
    private String serverKey;

    /**
     * 运行时服务描述，仅用于本地执行，不参与序列化。
     */
    @ToString.Exclude
    @JSONField(serialize = false, deserialize = false)
    private McpServerDescriptor descriptor;

    public String resolveExposedName() {
        return exposedName == null || exposedName.isBlank() ? name : exposedName;
    }

    /**
     * 离线请求只允许执行管理员配置的本地 STDIO MCP。
     * 用户扩展、HTTP/SSE 以及缺少来源信息的工具均保持 fail-closed。
     */
    public boolean isOfflineEligible() {
        return origin == McpToolOrigin.CONFIGURED
                && McpServerDescriptor.TRANSPORT_TYPE_STDIO.equals(transportType);
    }

    public static String canonicalExposedName(String mcpId, String toolName) {
        return "mcp__" + normalizeName(mcpId) + "__" + normalizeName(toolName);
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
