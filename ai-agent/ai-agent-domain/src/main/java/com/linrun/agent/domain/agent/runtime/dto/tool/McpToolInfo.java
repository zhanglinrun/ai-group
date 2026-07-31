package com.linrun.agent.domain.agent.runtime.dto.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import com.linrun.agent.domain.agent.runtime.harness.ToolRiskLevel;
import com.linrun.agent.domain.agent.runtime.harness.ToolSideEffect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** Optional MCP output schema; absent schemas are handled fail-closed by the registry. */
    private String outputSchema;

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
    @JsonIgnore
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

    /**
     * Credential-free identity of the discovered schema and policy. A Run pins
     * this value so a later registry refresh cannot silently swap a tool
     * definition after it has been selected by {@code tool_search}.
     */
    public String definitionHash() {
        String canonical = String.join("\n",
                safe(mcpId), safe(resolveExposedName()), safe(name), safe(desc), safe(parameters), safe(outputSchema),
                safe(transportType), String.valueOf(origin), String.valueOf(riskLevel),
                String.valueOf(sideEffect), safe(serverKey),
                descriptor == null ? "" : safe(descriptor.getProtocolVersion()),
                descriptor == null ? "" : safe(descriptor.getVersion()),
                descriptor == null ? "" : safe(descriptor.getConfigHash()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static String canonicalExposedName(String mcpId, String toolName) {
        return "mcp__" + normalizeName(mcpId) + "__" + normalizeName(toolName);
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
