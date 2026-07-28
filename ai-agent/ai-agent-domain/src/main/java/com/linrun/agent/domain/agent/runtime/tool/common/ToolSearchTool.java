package com.linrun.agent.domain.agent.runtime.tool.common;

import com.linrun.agent.types.common.JsonUtils;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.exposure.ToolExposurePolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SearchExtraTools：搜索 deferred MCP 工具，并授权后续通过稳定代理执行。 */
@Data
public class ToolSearchTool implements BaseTool {

    private AgentContext agentContext;

    @Override
    public String getName() {
        return "tool_search";
    }

    @Override
    public String getDescription() {
        return "SearchExtraTools：搜索当前未直接暴露的 MCP 扩展工具。"
                + "返回目标的原生 input_schema 并授权本次 run 通过 execute_extra_tool 调用；"
                + "发现后不会改变后续模型轮次的工具 schema 集合。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "用能力和业务动作描述需要寻找的工具，例如：查询 Jira issue 或创建日历事件"
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "最多返回多少个工具"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("query"),
                "additionalProperties", false
        );
    }

    @Override
    public Object execute(Object input) {
        Map<?, ?> args = input instanceof Map<?, ?> map ? map : Map.of();
        Object queryValue = args.containsKey("query") ? args.get("query") : "";
        String query = StringUtils.trimToEmpty(String.valueOf(queryValue));
        if (query.isEmpty()) {
            return "tool_search requires a non-empty query";
        }
        ReactorConfig config = requireConfig();
        int defaultLimit = config.getToolExposureSearchDefaultLimit() == null
                ? 6 : Math.max(1, config.getToolExposureSearchDefaultLimit());
        int limit = parseLimit(args.get("limit"), defaultLimit);
        List<McpToolInfo> tools = ToolExposurePolicy.searchMcpTools(
                agentContext.getToolCollection(), query, limit);
        List<String> names = tools.stream().map(McpToolInfo::resolveExposedName).toList();
        agentContext.getAgentRunState().markToolsDiscovered(names);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("discoveredTools", tools.stream().map(tool -> {
            Map<String, Object> discovered = new LinkedHashMap<>();
            discovered.put("name", tool.resolveExposedName());
            discovered.put("description", StringUtils.defaultString(tool.getDesc()));
            discovered.put("input_schema", parseInputSchema(tool.getParameters()));
            discovered.put("execute_with", ExecuteExtraTool.NAME);
            return discovered;
        }).toList());
        result.put("instruction", names.isEmpty()
                ? "No matching deferred tools were found; continue with visible tools or explain the limitation."
                : "The matched tools are authorized for this run. Invoke them only through execute_extra_tool using the returned native input_schema; do not expect their schemas to appear in the next tool list.");
        return JsonUtils.toJson(result);
    }

    private Object parseInputSchema(String parameters) {
        if (StringUtils.isBlank(parameters)) {
            return Map.of();
        }
        try {
            return JsonUtils.parseTree(parameters);
        } catch (Exception ignored) {
            return parameters;
        }
    }

    private int parseLimit(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(20, number.intValue()));
        }
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private ReactorConfig requireConfig() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("tool_search 缺少运行时配置");
        }
        return agentContext.getRuntimeDependencies().requireReactorConfig();
    }
}
