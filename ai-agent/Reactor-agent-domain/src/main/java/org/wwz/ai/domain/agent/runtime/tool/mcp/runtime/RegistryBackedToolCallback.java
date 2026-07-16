package org.wwz.ai.domain.agent.runtime.tool.mcp.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.wwz.ai.domain.agent.runtime.dto.tool.McpToolInfo;
import org.wwz.ai.domain.agent.runtime.util.ToolSchemaNormalizer;

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
                .name(toolInfo.getName())
                .description(StringUtils.defaultString(toolInfo.getDesc()))
                .inputSchema(ToolSchemaNormalizer.normalizeSchema(toolInfo.getParameters(), toolInfo.getName()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        return execute(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return execute(toolInput, toolContext);
    }

    /**
     * 将 Spring AI 的工具调用统一路由到 registry。
     */
    private String execute(String toolInput, ToolContext toolContext) {
        WorkflowToolTraceContext trace = WorkflowToolTraceContext.from(toolContext);
        WorkflowToolTraceContext.TraceInvocation invocation = trace == null
                ? null
                : trace.begin(toolInfo.getName(), toolInput, toolContext);
        try {
            String result = mcpRegistry.executeTool(toolInfo.getMcpId(), toolInfo.getName(), toolInput);
            if (trace != null) {
                trace.finishSuccess(invocation, result);
            }
            return result;
        } catch (RuntimeException e) {
            if (trace != null) {
                trace.finishFailure(invocation, e);
            }
            log.error("Registry ToolCallback 调用失败: mcpId={}, toolName={}, reason={}",
                    toolInfo.getMcpId(), toolInfo.getName(), e.getMessage(), e);
            throw e;
        }
    }
}
