package com.linrun.agent.domain.agent.runtime.tool.factory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.CodeInterpreterTool;
import com.linrun.agent.domain.agent.runtime.tool.common.AnalyzeFileTool;
import com.linrun.agent.domain.agent.runtime.tool.common.DeepSearchTool;
import com.linrun.agent.domain.agent.runtime.tool.common.FileTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ImageGenerationTool;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ReportTool;
import com.linrun.agent.domain.agent.runtime.tool.common.WebFetchTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ToolSearchTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ExecuteExtraTool;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.GlobTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.GrepTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.ListDirectoryTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.ReadTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.ScriptRunnerTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.SkillTool;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.UserSkillTool;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpExtensionService;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRegistry;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillScriptRunnerClient;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillExtensionService;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.tool.exposure.ToolExposurePolicy;

import java.util.Arrays;
import java.util.List;

/**
 * 构建单次 Agent Loop 的 run-local 工具目录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolCollectionFactory {

    private final ReactorConfig reactorConfig;
    private final McpToolExecutor mcpToolExecutor;
    private final SkillRegistry skillRegistry;
    private final SkillRuntimeOptions skillRuntimeOptions;
    private final SkillScriptRunnerClient skillScriptRunnerClient;
    private final UserSkillExtensionService userSkillExtensionService;
    private final UserMcpExtensionService userMcpExtensionService;

    /** Unified loop tool catalog. Planning is a run-local tool, not a separate agent mode. */
    public ToolCollection buildForUnified(AgentContext agentContext, AgentRequest request) {
        ToolCollection toolCollection = build(agentContext, request);
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(agentContext);
        toolCollection.addTool(todoWriteTool);
        return toolCollection;
    }

    private ToolCollection build(AgentContext agentContext, AgentRequest request) {
        ReactorRuntimeDependencies runtimeDependencies = requireRuntimeDependencies(agentContext);
        ToolCollection toolCollection = new ToolCollection();
        toolCollection.setAgentContext(agentContext);
        toolCollection.setMcpToolExecutor(runtimeDependencies.getOptionalMcpToolExecutor());
        boolean online = !Boolean.FALSE.equals(request.getOnline());

        // The business bridge is read-only and owner-bound. Keep it absent from
        // isolated runtimes that deliberately do not provide the typed BFF port.
        if (runtimeDependencies.getPlatformContextPort() != null) {
            PlatformContextTool platformContextTool = new PlatformContextTool();
            platformContextTool.setAgentContext(agentContext);
            toolCollection.addTool(platformContextTool);
        }

        FileTool fileTool = new FileTool();
        fileTool.setAgentContext(agentContext);
        toolCollection.addTool(fileTool);
        if (request.getSessionFiles() != null && !request.getSessionFiles().isEmpty()) {
            AnalyzeFileTool analyzeFileTool = new AnalyzeFileTool();
            analyzeFileTool.setAgentContext(agentContext);
            toolCollection.addTool(analyzeFileTool);
        }

        List<String> agentToolList = Arrays.stream(reactorConfig.getMultiAgentToolListMap()
                        .getOrDefault("default", "search,web_fetch,code,report")
                        .split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();

        if (agentToolList.contains("code")) {
            CodeInterpreterTool codeInterpreterTool = new CodeInterpreterTool();
            codeInterpreterTool.setAgentContext(agentContext);
            toolCollection.addTool(codeInterpreterTool);
        }
        if (agentToolList.contains("report")) {
            ReportTool reportTool = new ReportTool();
            reportTool.setAgentContext(agentContext);
            toolCollection.addTool(reportTool);
        }
        if (online && agentToolList.contains("search")) {
            DeepSearchTool deepSearchTool = new DeepSearchTool();
            deepSearchTool.setAgentContext(agentContext);
            toolCollection.addTool(deepSearchTool);
        }
        if (online && agentToolList.contains("web_fetch")) {
            WebFetchTool webFetchTool = new WebFetchTool();
            webFetchTool.setAgentContext(agentContext);
            toolCollection.addTool(webFetchTool);
        }
        if (agentToolList.contains("image_generation")) {
            ImageGenerationTool imageGenerationTool = new ImageGenerationTool();
            imageGenerationTool.setAgentContext(agentContext);
            toolCollection.addTool(imageGenerationTool);
        }
        if (skillRegistry.isEnabled()
                && !skillRegistry.listSkills().isEmpty()
                && skillRuntimeOptions.isAgentLoopEnabled()) {
            registerSkillTools(toolCollection, agentContext);
        }
        if (!userSkillExtensionService.listEnabled(request.getOwnerId()).isEmpty()) {
            UserSkillTool userSkillTool = new UserSkillTool(userSkillExtensionService);
            userSkillTool.setAgentContext(agentContext);
            toolCollection.addTool(userSkillTool);
        }

        try {
            List<McpToolInfo> configuredMcpTools;
            if (request.getProfileClientIds() != null && !request.getProfileClientIds().isEmpty()) {
                configuredMcpTools = online
                        ? mcpToolExecutor.discoverToolsForClients(request.getProfileClientIds())
                        : mcpToolExecutor.discoverOfflineEligibleToolsForClients(request.getProfileClientIds());
            } else {
                configuredMcpTools = online
                        ? mcpToolExecutor.discoverConfiguredTools()
                        : mcpToolExecutor.discoverOfflineEligibleConfiguredTools();
            }
            for (McpToolInfo toolInfo : configuredMcpTools) {
                if (toolInfo != null && (online || toolInfo.isOfflineEligible())) {
                    toolCollection.addMcpTool(toolInfo);
                }
            }
            if (online) {
                for (McpToolInfo toolInfo : userMcpExtensionService.discoverEnabledTools(request.getOwnerId())) {
                    toolCollection.addMcpTool(toolInfo);
                }
            }
        } catch (Exception e) {
            log.error("{} add mcp tool failed", agentContext.getRequestId(), e);
        }
        if (ToolExposurePolicy.shouldAttachSearchTool(
                reactorConfig, toolCollection.getMcpToolMap().size())) {
            ToolSearchTool toolSearchTool = new ToolSearchTool();
            toolSearchTool.setAgentContext(agentContext);
            toolCollection.addTool(toolSearchTool);
            toolCollection.addTool(new ExecuteExtraTool());
        }
        return toolCollection;
    }

    private ReactorRuntimeDependencies requireRuntimeDependencies(AgentContext agentContext) {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("AgentToolCollectionFactory 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies();
    }

    private void registerSkillTools(ToolCollection toolCollection, AgentContext agentContext) {
        SkillTool skillTool = new SkillTool(skillRegistry);
        skillTool.setAgentContext(agentContext);
        toolCollection.addTool(skillTool);

        ReadTool readTool = new ReadTool(skillRegistry, skillRuntimeOptions);
        readTool.setAgentContext(agentContext);
        toolCollection.addTool(readTool);

        ListDirectoryTool listDirectoryTool = new ListDirectoryTool(skillRegistry, skillRuntimeOptions);
        listDirectoryTool.setAgentContext(agentContext);
        toolCollection.addTool(listDirectoryTool);

        GlobTool globTool = new GlobTool(skillRegistry, skillRuntimeOptions);
        globTool.setAgentContext(agentContext);
        toolCollection.addTool(globTool);

        GrepTool grepTool = new GrepTool(skillRegistry, skillRuntimeOptions);
        grepTool.setAgentContext(agentContext);
        toolCollection.addTool(grepTool);

        ScriptRunnerTool scriptRunnerTool = new ScriptRunnerTool(skillRegistry, skillRuntimeOptions, skillScriptRunnerClient);
        scriptRunnerTool.setAgentContext(agentContext);
        toolCollection.addTool(scriptRunnerTool);
    }

}
