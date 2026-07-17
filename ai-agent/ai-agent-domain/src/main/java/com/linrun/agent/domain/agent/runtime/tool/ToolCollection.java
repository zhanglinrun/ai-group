package com.linrun.agent.domain.agent.runtime.tool;


/**
 * 工具集合类 - 管理可用的工具
 */

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 智能体工具集合管理类
 * 核心作用：统一管理智能体可调用的所有工具，包含两类核心工具：
 * 1. 基础工具（BaseTool）：本地实现的工具（如文件解析、计划管理、检索工具）；
 * 2. MCP 工具：由 McpToolExecutor 统一发现并执行的远程工具；
 * 附加能力：管理「数字员工」配置，关联工具与数字员工的映射关系，支撑动态工具扩展。
 *
 * 设计特点：
 * - 采用Map存储工具，通过工具名快速索引（O(1)查询效率）；
 * - 封装工具的添加、获取、执行逻辑，对外提供统一的execute入口；
 * - 关联AgentContext上下文，支撑工具执行时的上下文依赖。
 *
 * @author （可补充作者信息）
 * @date （可补充日期）
 */
@Data
@Slf4j
public class ToolCollection {
    /**
     * 基础工具映射表（核心工具容器）
     * Key：工具名称（唯一标识，如"todo_write"、"file_tool"）；
     * Value：BaseTool子类实例（本地实现的具体工具）；
     * 用途：存储所有本地可执行的基础工具，支持快速查询和调用。
     */
    private Map<String, BaseTool> toolMap;

    /**
     * MCP工具信息映射表（远程工具容器）
     * Key：工具名称（唯一标识，如"remoteSearchTool"、"mcpAnalysisTool"）；
     * Value：McpToolInfo（远程MCP工具的元信息：名称、描述、参数、服务地址）；
     * 用途：存储远程MCP工具的配置信息，执行时通过该信息调用远程服务。
     */
    private Map<String, McpToolInfo> mcpToolMap;

    /**
     * 关联的智能体上下文
     * 用途：工具执行时需要依赖上下文数据（如requestId、文件列表、输出器），
     * 尤其MCP工具调用时需传递上下文完成全链路追踪。
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JSONField(serialize = false, deserialize = false)
    private AgentContext agentContext;

    /**
     * MCP 工具统一执行器。
     * ToolCollection 不是 Spring Bean，因此必须由外部显式注入。
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JSONField(serialize = false, deserialize = false)
    private McpToolExecutor mcpToolExecutor;

    /**
     * 无参构造方法
     * 初始化工具映射表：默认创建空的HashMap，避免空指针异常。
     */
    public ToolCollection() {
        this.toolMap = new LinkedHashMap<>();
        this.mcpToolMap = new LinkedHashMap<>();
    }

    /**
     * 添加基础工具到集合
     * @param tool 基础工具实例（BaseTool子类，如TodoWriteTool、FileTool）
     * 说明：工具名称作为Key，重复添加会覆盖原有同名称工具。
     */
    public void addTool(BaseTool tool) {
        toolMap.put(tool.getName(), tool);
    }

    /**
     * 根据工具名称获取基础工具
     * @param name 工具名称（唯一标识）
     * @return BaseTool 基础工具实例：存在则返回，不存在返回null。
     */
    public BaseTool getTool(String name) {
        return toolMap.get(name);
    }

    /**
     * 添加MCP远程工具到集合
     * @param toolInfo MCP 工具元信息
     * 说明：直接接收完整 McpToolInfo，重复添加时同名工具会被覆盖。
     */
    public void addMcpTool(McpToolInfo toolInfo) {
        if (toolInfo == null || StringUtils.isBlank(toolInfo.getName())) {
            log.warn("requestId:{} addMcpTool skipped, invalid toolInfo: {}",
                    agentContext != null ? agentContext.getRequestId() : "unknown", toolInfo);
            return;
        }
        String exposedName = toolInfo.resolveExposedName();
        McpToolInfo previous = mcpToolMap.put(exposedName, toolInfo);
        if (previous != null && previous != toolInfo) {
            log.warn("requestId:{} duplicate exposed MCP tool replaced: {}",
                    agentContext != null ? agentContext.getRequestId() : "unknown", exposedName);
        }
    }

    /**
     * 根据工具名称获取MCP工具信息
     * @param name 工具名称（唯一标识）
     * @return McpToolInfo MCP工具元信息：存在则返回，不存在返回null。
     */
    public McpToolInfo getMcpTool(String name) {
        return mcpToolMap.get(name);
    }

    /**
     * Resolve a model-emitted tool name against this active turn view.
     *
     * <p>MCP schemas use globally unique {@code mcp__server__tool} names, but
     * users often mention the remote MCP name directly in their request. Some
     * providers then echo that remote name in the function call even though the
     * canonical schema was supplied. Accept that alias only when exactly one
     * exposed MCP tool owns it. Hidden tools are not considered and duplicate
     * remote names remain rejected, so the active-view permission boundary is
     * preserved.</p>
     */
    public String resolveActiveToolName(String requestedName) {
        if (StringUtils.isBlank(requestedName)
                || toolMap.containsKey(requestedName)
                || mcpToolMap.containsKey(requestedName)) {
            return requestedName;
        }
        String resolvedName = null;
        for (Map.Entry<String, McpToolInfo> entry : mcpToolMap.entrySet()) {
            McpToolInfo toolInfo = entry.getValue();
            if (toolInfo == null || !StringUtils.equals(requestedName, toolInfo.getName())) {
                continue;
            }
            if (resolvedName != null && !StringUtils.equals(resolvedName, entry.getKey())) {
                return requestedName;
            }
            resolvedName = entry.getKey();
        }
        return StringUtils.defaultIfBlank(resolvedName, requestedName);
    }

    /**
     * 为单轮模型调用创建浅拷贝视图。工具实例和 MCP 元信息保持复用，
     * 但未选中的工具既不会进入 Schema，也不能在该轮执行。
     */
    public ToolCollection selectedView(Collection<String> exposedNames) {
        Set<String> selected = exposedNames == null
                ? Set.of()
                : new LinkedHashSet<>(exposedNames);
        ToolCollection view = new ToolCollection();
        view.setAgentContext(agentContext);
        view.setMcpToolExecutor(mcpToolExecutor);
        toolMap.forEach((name, tool) -> {
            if (selected.contains(name)) {
                view.addTool(tool);
            }
        });
        mcpToolMap.forEach((name, tool) -> {
            if (selected.contains(name)) {
                view.addMcpTool(tool);
            }
        });
        return view;
    }

    public int toolCount() {
        return toolMap.size() + mcpToolMap.size();
    }

    public int estimateSchemaChars() {
        int chars = 0;
        for (BaseTool tool : toolMap.values()) {
            chars += safeLength(tool.getName()) + safeLength(tool.getDescription())
                    + safeLength(String.valueOf(tool.toParams()));
        }
        for (McpToolInfo tool : mcpToolMap.values()) {
            chars += safeLength(tool.resolveExposedName()) + safeLength(tool.getDesc())
                    + safeLength(tool.getParameters());
        }
        return chars;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * 统一执行工具（核心方法）
     * 逻辑分支：
     * 1. 优先执行基础工具（本地）；
     * 2. 基础工具不存在时执行MCP远程工具；
     * 3. 工具不存在时记录错误日志并返回null。
     * @param name 工具名称（唯一标识）
     * @param toolInput 工具输入参数（Object类型，适配不同工具的参数格式，如String/JSONObject）
     * @return Object 工具执行结果：
     *         - 基础工具：返回 tool.execute() 的原始结果对象；
     *         - MCP工具：返回远程调用的响应结果字符串；
     *         - 工具不存在：返回null。
     */
    public Object execute(String name, Object toolInput) {
        // 分支1：执行本地基础工具
        if (toolMap.containsKey(name)) {
            BaseTool tool = getTool(name);
            return tool.execute(toolInput);
        }
        // 分支2：执行远程MCP工具
        else if (mcpToolMap.containsKey(name)) {
            McpToolInfo toolInfo = mcpToolMap.get(name);
            McpToolExecutor executor = mcpToolExecutor;
            if (executor == null) {
                log.error("requestId:{} execute mcp tool {} failed, McpToolExecutor not found",
                        agentContext != null ? agentContext.getRequestId() : "unknown", name);
                String message = "Tool" + name + " Error.";
                return ToolResultPayload.failure(message, message, null, "McpToolExecutor not found");
            }
            return executor.executeTool(toolInfo, toolInput);
        }
        // 分支3：工具不存在，记录错误日志
        else {
            log.error("Error: Unknown tool {}", name);
        }
        return null;
    }

    @Override
    public String toString() {
        return "ToolCollection(" +
                "toolMap=" + (toolMap != null ? toolMap.keySet() : "null") +
                ", mcpToolMap=" + (mcpToolMap != null ? mcpToolMap.keySet() : "null") +
                ')';
    }
}
