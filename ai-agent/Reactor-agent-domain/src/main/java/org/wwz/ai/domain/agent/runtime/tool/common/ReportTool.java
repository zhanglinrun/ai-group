package org.wwz.ai.domain.agent.runtime.tool.common;


import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamListener;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamRequest;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.CodeInterpreterRequest;
import org.wwz.ai.domain.agent.runtime.dto.CodeInterpreterResponse;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.util.StringUtil;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.config.ReactorToolRequestHeaders;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ReportToolOutput;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRefMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Slf4j
@Data

public class ReportTool implements BaseTool {
    private AgentContext agentContext;

    @Override
    public String getName() {
        return "report_tool";
    }

    @Override
    public String getDescription() {
        String desc = "这是一个报告工具，可以通过编写HTML、MarkDown报告";
        ReactorConfig reactorConfig = requireReactorConfig();
        return reactorConfig.getReportToolDesc().isEmpty() ? desc : reactorConfig.getReportToolDesc();
    }

    @Override
    public Map<String, Object> toParams() {

        ReactorConfig reactorConfig = requireReactorConfig();
        if (!reactorConfig.getReportToolParams().isEmpty()) {
            return reactorConfig.getReportToolParams();
        }

        Map<String, Object> taskParam = new HashMap<>();
        taskParam.put("type", "string");
        taskParam.put("description", "需要完成的任务以及完成任务需要的数据，需要尽可能详细");
        // execute() 强制要求 fileName，且 fileType 决定产物格式（html/markdown/ppt）。
        // 默认 schema 必须声明这些字段，否则 LLM 无法传参：fileName 缺失直接失败、
        // fileType 缺失时用户在前端选择的 文档/PPT 输出格式会退化成 HTML。
        Map<String, Object> fileNameParam = new HashMap<>();
        fileNameParam.put("type", "string");
        fileNameParam.put("description", "产物文件名（不含扩展名），例如：销售分析报告");
        Map<String, Object> fileDescParam = new HashMap<>();
        fileDescParam.put("type", "string");
        fileDescParam.put("description", "产物文件的简要描述");
        Map<String, Object> fileTypeParam = new HashMap<>();
        fileTypeParam.put("type", "string");
        fileTypeParam.put("enum", Arrays.asList("html", "markdown", "ppt"));
        fileTypeParam.put("description", "报告产物格式：html=网页报告，markdown=文档报告，ppt=演示文稿。用户指定了输出格式时必须严格使用对应值");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("task", taskParam);
        properties.put("fileName", fileNameParam);
        properties.put("fileDescription", fileDescParam);
        properties.put("fileType", fileTypeParam);
        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("task", "fileName"));

        return parameters;
    }

    @Override
    public Object execute(Object input) {
        try {
            Map<String, Object> params = (Map<String, Object>) input;
            String task = (String) params.get("task");
            String fileDescription = (String) params.get("fileDescription");
            String fileName = (String) params.get("fileName");
            String fileType = (String) params.get("fileType");

            if (StringUtils.isBlank(fileName)) {
                String errMessage = "文件名参数为空，无法生成报告。";
                log.error("{} {}", agentContext.getRequestId(), errMessage);
                return buildFailurePayload(errMessage);
            }

            List<String> fileNames = agentContext.getProductFiles().stream().map(File::getFileName).collect(Collectors.toList());
            Map<String, Object> streamMode = new HashMap<>();
            streamMode.put("mode", "token");
            streamMode.put("token", 10);
            CodeInterpreterRequest request = CodeInterpreterRequest.builder()
                    .requestId(agentContext.getSessionId()) // 适配多轮对话
                    .query(agentContext.getQuery())
                    .task(task)
                    .fileNames(fileNames)
                    .fileName(fileName)
                    .fileDescription(fileDescription)
                    .stream(true)
                    .contentStream(agentContext.getIsStream())
                    .streamMode(streamMode)
                    .fileType(fileType)
                    .templateType(agentContext.getTemplateType())
                    .build();
            ToolArtifactSource artifactSource = agentContext.requireCurrentToolArtifactSource(getName());
            // 调用流式 API
            Future<ToolResultPayload> future = callCodeAgentStream(request, artifactSource);
            return future.get();
        } catch (Exception e) {
            log.error("{} report_tool execute failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            return buildFailurePayload("report_tool 执行失败：" + e.getMessage());
        }
    }

    /**
     * 调用 CodeAgent
     */
    public CompletableFuture<ToolResultPayload> callCodeAgentStream(CodeInterpreterRequest codeRequest,
                                                                    ToolArtifactSource artifactSource) {
        CompletableFuture<ToolResultPayload> future = new CompletableFuture<>();
        try {
            ReactorConfig reactorConfig = requireReactorConfig();
            String url = reactorConfig.getCodeInterpreterUrl() + "/v1/tool/report";
            log.info("{} report_tool request started fileType={} inputFileCount={} stream={}",
                    agentContext.getRequestId(), codeRequest.getFileType(),
                    codeRequest.getFileNames() == null ? 0 : codeRequest.getFileNames().size(),
                    codeRequest.getStream());
            String[] interval = reactorConfig.getMessageInterval().getOrDefault("report", "1,4").split(",");
            int firstInterval = Integer.parseInt(interval[0]);
            int sendInterval = Integer.parseInt(interval[1]);
            String messageId = StringUtil.getUUID();
            String toolCallId = artifactSource == null ? null : artifactSource.getToolCallId();
            String digitalEmployee = agentContext.getToolCollection().getDigitalEmployee(getName());
            java.util.concurrent.atomic.AtomicReference<CodeInterpreterResponse> finalResponseRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger(1);
            StringBuilder incrementalBuffer = new StringBuilder();

            requireRemoteStreamPort().openStream(RemoteStreamRequest.builder()
                    .method("POST")
                    .url(url)
                    .headers(ReactorToolRequestHeaders.json(reactorConfig))
                    .body(JSONObject.toJSONString(codeRequest))
                    .connectTimeoutSeconds(60000L)
                    .readTimeoutSeconds(60000L)
                    .writeTimeoutSeconds(60000L)
                    .callTimeoutSeconds(6000L)
                    .build(), new RemoteStreamListener() {
                @Override
                public void onOpen() {
                    log.info("{} report_tool stream opened", agentContext.getRequestId());
                }

                @Override
                public void onLine(String line) {
                    if (!line.startsWith("data:")) {
                        return;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data) || data.startsWith("heartbeat")) {
                        return;
                    }
                    int currentIndex = index.getAndIncrement();
                    if (currentIndex == 1 || currentIndex % 100 == 0) {
                        log.debug("{} report_tool event received sequence={} payloadChars={}",
                                agentContext.getRequestId(), currentIndex, data.length());
                    }
                    CodeInterpreterResponse codeResponse = JSONObject.parseObject(data, CodeInterpreterResponse.class);
                    codeResponse.setToolCallId(toolCallId);
                    if (Boolean.TRUE.equals(codeResponse.getIsFinal())) {
                        String validationError = validateFinalResponse(codeResponse);
                        if (validationError != null) {
                            log.warn("{} report_tool rejected invalid final response reason={}",
                                    agentContext.getRequestId(), validationError);
                            if (!future.isDone()) {
                                future.complete(buildFailurePayload(
                                        "report_tool 执行失败：上游未返回有效报告产物（" + validationError + "）。"));
                            }
                            return;
                        }
                        finalResponseRef.set(codeResponse);
                        if (Objects.nonNull(codeResponse.getFileInfo())) {
                            for (CodeInterpreterResponse.FileInfo fileInfo : codeResponse.getFileInfo()) {
                                File file = File.builder()
                                        .fileName(codeRequest.getFileName())
                                        .fileSize(fileInfo.getFileSize())
                                        .ossUrl(fileInfo.getOssUrl())
                                        .domainUrl(fileInfo.getDomainUrl())
                                        .description(codeRequest.getFileDescription())
                                        .isInternalFile(false)
                                        .build();
                                agentContext.registerGeneratedArtifact(artifactSource, file);
                            }
                        }
                        agentContext.getPrinter().send(messageId, codeRequest.getFileType(), codeResponse, digitalEmployee, true);
                        return;
                    }
                    incrementalBuffer.append(codeResponse.getData());
                    if (currentIndex == firstInterval || currentIndex % sendInterval == 0) {
                        codeResponse.setData(incrementalBuffer.toString());
                        agentContext.getPrinter().send(messageId, codeRequest.getFileType(), codeResponse, digitalEmployee, false);
                        incrementalBuffer.setLength(0);
                    }
                }

                @Override
                public void onClosed() {
                    if (future.isDone()) {
                        return;
                    }
                    CodeInterpreterResponse codeResponse = finalResponseRef.get();
                    if (codeResponse == null) {
                        future.complete(buildFailurePayload(
                                "report_tool 执行失败：上游流在有效最终响应到达前关闭。"));
                        return;
                    }
                    future.complete(buildSuccessPayload(codeRequest, codeResponse, resolveResult(codeResponse)));
                }

                @Override
                public void onFailure(Throwable throwable, Integer statusCode, String responseBody) {
                    log.error("{} report_tool upstream failed statusCode={} responseChars={} errorType={}",
                            agentContext.getRequestId(), statusCode,
                            responseBody == null ? 0 : responseBody.length(),
                            throwable == null ? "unknown" : throwable.getClass().getSimpleName());
                    if (!future.isDone()) {
                        if (statusCode != null) {
                            future.complete(buildFailurePayload("report_tool 执行失败：上游服务返回异常状态 " + statusCode + "。"));
                        } else {
                            future.complete(buildFailurePayload("report_tool 执行失败：" + throwable.getMessage()));
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("{} report_tool request failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            future.complete(buildFailurePayload("report_tool 执行失败：" + e.getMessage()));
        }

        return future;
    }

    private String validateFinalResponse(CodeInterpreterResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.getIsFinal())) {
            return "missing final marker";
        }
        String result = resolveResult(response);
        if (StringUtils.isBlank(result) || "report_tool 执行失败".equals(StringUtils.trim(result))) {
            return "empty report content";
        }
        if (response.getFileInfo() == null || response.getFileInfo().isEmpty()) {
            return "missing report artifact";
        }
        boolean hasValidArtifact = response.getFileInfo().stream()
                .filter(Objects::nonNull)
                .anyMatch(fileInfo -> StringUtils.isNotBlank(fileInfo.getOssUrl())
                        || StringUtils.isNotBlank(fileInfo.getDomainUrl()));
        return hasValidArtifact ? null : "report artifact has no storage reference";
    }

    private String resolveResult(CodeInterpreterResponse response) {
        if (response == null) {
            return "";
        }
        return StringUtils.isNotBlank(response.getData())
                ? response.getData()
                : StringUtils.defaultString(response.getCodeOutput());
    }

    /**
     * 报告工具需要保留文件类型、正文内容和文件引用，便于历史回放还原 Markdown/HTML/PPT 展示。
     */
    private ToolResultPayload buildSuccessPayload(CodeInterpreterRequest codeRequest,
                                                  CodeInterpreterResponse codeResponse,
                                                  String result) {
        String normalizedResult = StringUtils.defaultString(result);
        return ToolResultPayload.structured(
                normalizedResult,
                normalizedResult,
                ReportToolOutput.builder()
                        .fileType(codeRequest.getFileType())
                        .summary(abbreviate(normalizedResult, 160))
                        .content(normalizedResult)
                        .fileRefs(ToolFileRefMapper.fromCodeInterpreterFileInfo(codeResponse == null ? null : codeResponse.getFileInfo()))
                        .build()
        );
    }

    /**
     * 失败路径统一返回最小 typed output，避免 rich tool 落回空结构。
     */
    private ToolResultPayload buildFailurePayload(String message) {
        String userMessage = "报告生成服务暂时不可用，正在切换备用交付方式。";
        String llmInstruction = "报告服务本轮不可用。不要再次调用 report_tool；请立即使用可用文件能力生成用户要求格式的交付物，并正常完成任务。";
        return ToolResultPayload.failure(
                userMessage,
                llmInstruction,
                ReportToolOutput.builder()
                        .summary(userMessage)
                        .content("")
                        .build(),
                message
        );
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return StringUtils.defaultString(text);
        }
        return text.substring(0, maxLen) + "...";
    }

    private ReactorConfig requireReactorConfig() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("ReportTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireReactorConfig();
    }

    private RemoteStreamPort requireRemoteStreamPort() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("ReportTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireRemoteStreamPort();
    }
}
