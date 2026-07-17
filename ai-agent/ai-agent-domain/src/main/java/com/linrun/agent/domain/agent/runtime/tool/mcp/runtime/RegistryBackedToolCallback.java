package com.linrun.agent.domain.agent.runtime.tool.mcp.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.util.ToolSchemaNormalizer;

/**
 * 基于 McpRegistry 的 ToolCallback 实现。
 * 只缓存工具元信息，实际调用统一回到 registry，由 registry 按传输协议选择最合适的执行策略。
 */
@Slf4j
@RequiredArgsConstructor
public class RegistryBackedToolCallback implements ToolCallback {

    /**
     * MCP 统一注册中心。
     */
    private final McpRegistry mcpRegistry;

    /**
     * 工具元信息快照。
     */
    private final McpToolInfo toolInfo;

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(toolInfo.resolveExposedName())
                .description(StringUtils.defaultString(toolInfo.getDesc()))
                .inputSchema(ToolSchemaNormalizer.normalizeSchema(toolInfo.getParameters(), toolInfo.resolveExposedName()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        return execute(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return execute(toolInput);
    }

    /**
     * 将 Spring AI 的工具调用统一路由到 registry。
     */
    private String execute(String toolInput) {
        try {
            ToolResultPayload payload = mcpRegistry.executeTool(toolInfo.getMcpId(), toolInfo.getName(), toolInput);
            if (payload == null) {
                payload = ToolResultPayload.failure(
                        "Tool" + toolInfo.getName() + " Error.",
                        "Tool" + toolInfo.getName() + " Error.",
                        null,
                        "MCP registry returned null payload"
                );
            }
            String observation = StringUtils.defaultIfBlank(payload.getLlmObservation(), payload.getToolResult());
            return observation;
        } catch (RuntimeException e) {
            log.error("Registry ToolCallback 调用失败: mcpId={}, toolName={}, reason={}",
                    toolInfo.getMcpId(), toolInfo.getName(), e.getMessage(), e);
            throw e;
        }
    }
}
