package com.linrun.agent.domain.agent.runtime.tool.common;


import com.linrun.agent.types.common.JsonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamListener;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamRequest;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamSession;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.CodeInterpreterResponse;
import com.linrun.agent.domain.agent.runtime.dto.DataAnalysisRequest;
import com.linrun.agent.domain.agent.runtime.dto.DataAnalysisResponse;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.util.StringUtil;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DataAnalysisToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRefMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter.toArtifactRefs;

@Slf4j
@Data
public class DataAnalysisTool implements BaseTool {
    private static final long TOOL_TIMEOUT_SECONDS = 300L;
    private static final long CONNECT_TIMEOUT_SECONDS = 30L;
    private static final long READ_TIMEOUT_SECONDS = 300L;
    private static final long WRITE_TIMEOUT_SECONDS = 60L;

    private AgentContext agentContext;
    private volatile RemoteStreamSession activeStreamSession;

    @Override
    public String getName() {
        return "data_analysis";
    }

    @Override
    public String getDescription() {
        String desc = "这是一个数据分析工具，可以查询并分析数据";
        ReactorConfig reactorConfig = requireReactorConfig();
        StringBuilder description = new StringBuilder(reactorConfig.getDataAnalysisToolDesc().isEmpty() ? desc : reactorConfig.getDataAnalysisToolDesc());
        return description.toString();
    }

    @Override
    public Map<String, Object> toParams() {
        ReactorConfig reactorConfig = requireReactorConfig();
        if (!reactorConfig.getDataAnalysisToolParams().isEmpty()) {
            return reactorConfig.getDataAnalysisToolParams();
        }

        Map<String, Object> taskParam = new HashMap<>();
        taskParam.put("type", "string");
        taskParam.put("description", "task");

        Map<String, Object> businessKnowledgeParam = new HashMap<>();
        businessKnowledgeParam.put("type", "string");
        businessKnowledgeParam.put("description", "businessKnowledge");

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("task", taskParam);
        properties.put("businessKnowledge", businessKnowledgeParam);
        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("task", "businessKnowledge"));

        return parameters;
    }

    @Override
    public Object execute(Object input) {
        return buildFailurePayload("data_analysis 已下线，请使用文件分析或深度调研。");
    }

    public CompletableFuture<ToolResultPayload> callAutoAnalysisStream(DataAnalysisRequest analysisRequest,
                                                                       ToolArtifactSource artifactSource) {
        return CompletableFuture.completedFuture(
                buildFailurePayload("data_analysis 已下线，请使用文件分析或深度调研。"));
    }

    private void cancelActiveStream() {
        RemoteStreamSession session = activeStreamSession;
        if (session != null) {
            session.cancel();
        }
    }

    /**
     * 数据分析结果需要保留任务文本、结果摘要和文件引用，便于 replay 还原分析卡片。
     */
    private ToolResultPayload buildSuccessPayload(DataAnalysisRequest request,
                                                  String data,
                                                  List<CodeInterpreterResponse.FileInfo> fileInfo) {
        String normalizedData = StringUtils.defaultIfBlank(data, "分析结果为空").trim();
        return ToolResultPayload.structured(
                normalizedData,
                normalizedData,
                DataAnalysisToolOutput.builder()
                        .task(request.getTask())
                        .summary(abbreviate(normalizedData, 160))
                        .content(normalizedData)
                        .fileRefs(ToolFileRefMapper.fromCodeInterpreterFileInfo(fileInfo))
                        .build()
        );
    }

    /**
     * 失败结果统一返回最小 typed output，避免只剩日志没有账本事实。
     */
    private ToolResultPayload buildFailurePayload(String message) {
        return ToolResultPayload.failure(
                message,
                message,
                DataAnalysisToolOutput.builder()
                        .summary(message)
                        .content("")
                        .build(),
                message
        );
    }

    private String abbreviate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private ReactorConfig requireReactorConfig() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("DataAnalysisTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireReactorConfig();
    }

    private RemoteStreamPort requireRemoteStreamPort() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("DataAnalysisTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireRemoteStreamPort();
    }
}
