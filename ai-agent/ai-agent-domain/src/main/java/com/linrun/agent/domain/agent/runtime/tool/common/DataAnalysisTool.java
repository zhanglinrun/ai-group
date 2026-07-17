package com.linrun.agent.domain.agent.runtime.tool.common;


import com.alibaba.fastjson.JSONObject;
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
import com.linrun.agent.domain.agent.runtime.util.StringUtil;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DataAnalysisToolOutput;
import com.linrun.agent.domain.agent.reactor.model.response.AgentResponse;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRefMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

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
        try {
            Map<String, Object> params = (Map<String, Object>) input;
            String task = (String) params.getOrDefault("task", "");
            String businessKnowledge = (String) params.getOrDefault("businessKnowledge", "");

            DataAnalysisRequest request = DataAnalysisRequest.builder()
                    .request_id(agentContext.getSessionId())
                    .erp("reactor")
                    .task(task)
                    .modelCodeList(Arrays.asList("modelCode"))
                    .businessKnowledge(businessKnowledge)
                    .build();
            ToolArtifactSource artifactSource = agentContext.requireCurrentToolArtifactSource(getName());

            // 调用流式 API
            CompletableFuture<ToolResultPayload> future = callAutoAnalysisStream(request, artifactSource);
            return AgentFutureWaiter.await(future, agentContext, Duration.ofSeconds(TOOL_TIMEOUT_SECONDS));
        } catch (AgentFutureWaiter.RunDeadlineExceededException e) {
            cancelActiveStream();
            return buildFailurePayload("data_analysis 执行失败：Agent 运行时间预算已耗尽。");
        } catch (AgentFutureWaiter.DownstreamAbortedException e) {
            cancelActiveStream();
            return buildFailurePayload("data_analysis 执行已取消：客户端连接已断开。");
        } catch (TimeoutException e) {
            cancelActiveStream();
            return buildFailurePayload("data_analysis 执行超时，已取消远端任务。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelActiveStream();
            return buildFailurePayload("data_analysis 执行被中断，已取消远端任务。");
        } catch (Exception e) {
            log.error("{} auto_analysis execute failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            String message = "data_analysis 执行失败：" + StringUtils.defaultIfBlank(e.getMessage(), "未知异常");
            agentContext.getPrinter().send("tool_result", AgentResponse.ToolResult.builder()
                    .toolName("数据分析智能体")
                    .toolParam(new HashMap<>())
                    .toolResult("执行失败")
                    .build());
            return buildFailurePayload(message);
        } finally {
            activeStreamSession = null;
        }
    }

    /**
     * 调用自动分析 API。
     */
    public CompletableFuture<ToolResultPayload> callAutoAnalysisStream(DataAnalysisRequest analysisRequest,
                                                                       ToolArtifactSource artifactSource) {
        CompletableFuture<ToolResultPayload> future = new CompletableFuture<>();
        try {
            ReactorConfig duccConfig = requireReactorConfig();
            String url = duccConfig.getDataAnalysisUrl() + "/v1/tool/auto_analysis";
            log.info("{} auto_analysis request started modelCount={} maxSteps={} stream={}",
                    agentContext.getRequestId(),
                    analysisRequest.getModelCodeList() == null ? 0 : analysisRequest.getModelCodeList().size(),
                    analysisRequest.getMax_steps(), analysisRequest.getStream());
            String messageId = StringUtil.getUUID();
            String toolCallId = artifactSource == null ? null : artifactSource.getToolCallId();
            StringBuilder fullContentBuilder = new StringBuilder();
            List<CodeInterpreterResponse.FileInfo> finalFileInfo = new ArrayList<>();

            activeStreamSession = requireRemoteStreamPort().openStream(RemoteStreamRequest.builder()
                    .method("POST")
                    .url(url)
                    .headers(ReactorToolRequestHeaders.json(duccConfig))
                    .body(JSONObject.toJSONString(analysisRequest))
                    .connectTimeoutSeconds(CONNECT_TIMEOUT_SECONDS)
                    .readTimeoutSeconds(READ_TIMEOUT_SECONDS)
                    .writeTimeoutSeconds(WRITE_TIMEOUT_SECONDS)
                    .callTimeoutSeconds(TOOL_TIMEOUT_SECONDS)
                    .build(), new RemoteStreamListener() {
                @Override
                public void onOpen() {
                    log.info("{} auto_analysis stream opened", agentContext.getRequestId());
                }

                @Override
                public void onLine(String line) {
                    if (!line.startsWith("data:")) {
                        return;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data) || "heartbeat".equals(data)) {
                        return;
                    }
                    log.debug("{} auto_analysis event received payloadChars={}",
                            agentContext.getRequestId(), data.length());
                    try {
                        DataAnalysisResponse analysisResponse = JSONObject.parseObject(data, DataAnalysisResponse.class);
                        if (analysisResponse == null) {
                            return;
                        }
                        String chunkText = analysisResponse.getData() == null
                                ? ""
                                : String.valueOf(analysisResponse.getData());
                        if (StringUtils.isNotBlank(chunkText)) {
                            fullContentBuilder.append(chunkText).append("\n");
                        }
                        if (Objects.nonNull(analysisResponse.getFileInfo()) && !analysisResponse.getFileInfo().isEmpty()) {
                            finalFileInfo.clear();
                            finalFileInfo.addAll(analysisResponse.getFileInfo());
                            for (CodeInterpreterResponse.FileInfo fileInfo : analysisResponse.getFileInfo()) {
                                File file = File.builder()
                                        .fileName(fileInfo.getFileName())
                                        .ossUrl(fileInfo.getOssUrl())
                                        .domainUrl(fileInfo.getDomainUrl())
                                        .fileSize(fileInfo.getFileSize())
                                        .description(fileInfo.getFileName())
                                        .isInternalFile(false)
                                        .build();
                                agentContext.registerGeneratedArtifact(artifactSource, file);
                            }
                        }

                        analysisResponse.setTask(analysisRequest.getTask());
                        analysisResponse.setToolCallId(toolCallId);
                        if (Boolean.TRUE.equals(analysisResponse.getIsFinal())) {
                            analysisResponse.setData(fullContentBuilder.toString());
                            agentContext.getPrinter().send(messageId, "data_analysis",
                                    analysisResponse, null, true);
                        } else {
                            agentContext.getPrinter().send(messageId, "data_analysis",
                                    analysisResponse, null, false);
                        }
                    } catch (Exception parseException) {
                        log.warn("{} auto_analysis parse response failed errorType={}",
                                agentContext.getRequestId(), parseException.getClass().getSimpleName());
                    }
                }

                @Override
                public void onClosed() {
                    if (!future.isDone()) {
                        future.complete(buildSuccessPayload(analysisRequest, fullContentBuilder.toString(), finalFileInfo));
                    }
                }

                @Override
                public void onFailure(Throwable throwable, Integer statusCode, String responseBody) {
                    log.error("{} auto_analysis upstream failed statusCode={} responseChars={} errorType={}",
                            agentContext.getRequestId(), statusCode,
                            responseBody == null ? 0 : responseBody.length(),
                            throwable == null ? "unknown" : throwable.getClass().getSimpleName());
                    if (!future.isDone()) {
                        if (statusCode != null) {
                            future.complete(buildFailurePayload("data_analysis 执行失败：上游服务返回异常状态 " + statusCode + "。"));
                        } else {
                            future.complete(buildFailurePayload("data_analysis 执行失败：" + throwable.getMessage()));
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("{} auto_analysis request failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            future.complete(buildFailurePayload("data_analysis 执行失败：" + e.getMessage()));
        }

        return future;
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
