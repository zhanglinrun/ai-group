package com.linrun.agent.domain.agent.runtime.tool.common;

import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable model-facing proxy schema for deferred MCP tools.
 *
 * <p>The actual target lookup, authorization, native-schema validation and
 * execution are intentionally owned by {@code ToolDispatcher}. Keeping this
 * object as a normal {@link BaseTool} makes the provider tool array stable
 * without allowing callers to bypass the Harness pipeline.</p>
 */
public final class ExecuteExtraTool implements BaseTool {

    public static final String NAME = "execute_extra_tool";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "ExecuteExtraTool：执行已由 tool_search 发现并授权的延迟 MCP 工具。"
                + "传入精确 tool_name 和该工具原生参数 params；核心工具必须直接调用，不能通过本代理包装。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tool_name", Map.of(
                "type", "string",
                "description", "tool_search 返回的精确 canonical 工具名，例如 mcp__server__action"
        ));
        properties.put("params", Map.of(
                "type", "object",
                "description", "严格匹配目标工具原生 input_schema 的参数对象"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("tool_name", "params"),
                "additionalProperties", false
        );
    }

    @Override
    public Object execute(Object input) {
        String message = NAME + " must be resolved by ToolDispatcher before execution.";
        return ToolResultPayload.failure(message, message, null, message);
    }

    @Override
    public boolean isConcurrencySafe(Object input) {
        return false;
    }
}
