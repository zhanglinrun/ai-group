package com.linrun.agent.domain.agent.runtime.tool.common;


import com.linrun.agent.types.common.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamListener;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamRequest;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamSession;

import com.linrun.agent.domain.agent.runtime.dto.CodeInterpreterRequest;
import com.linrun.agent.domain.agent.runtime.dto.CodeInterpreterResponse;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.CodeInterpreterToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRefMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter.toArtifactRefs;

@Slf4j
@Data
public class CodeInterpreterTool implements BaseTool {

    private static final long TOOL_TIMEOUT_SECONDS = 300L;
    private static final long CONNECT_TIMEOUT_SECONDS = 30L;
    private static final long READ_TIMEOUT_SECONDS = 300L;
    private static final long WRITE_TIMEOUT_SECONDS = 60L;

    private AgentContext agentContext;
    private volatile RemoteStreamSession activeStreamSession;

    @Override
    public String getName() {
        return "code_interpreter";
    }

    @Override
    public String getDescription() {
        String desc = "这是一个代码工具，可以通过编写代码完成数据处理、数据分析、图表生成等任务";
        ReactorConfig reactorConfig = requireReactorConfig();
        return reactorConfig.getCodeAgentDesc().isEmpty() ? desc : reactorConfig.getCodeAgentDesc();
    }

    @Override
    public Map<String, Object> toParams() {

        ReactorConfig reactorConfig = requireReactorConfig();
        if (!reactorConfig.getCodeAgentParams().isEmpty()) {
            return reactorConfig.getCodeAgentParams();
        }

        Map<String, Object> taskParam = new HashMap<>();
        taskParam.put("type", "string");
        taskParam.put("description", "需要完成的任务以及完成任务需要的数据，需要尽可能详细");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("task", taskParam);
        parameters.put("properties", properties);
        parameters.put("required", Collections.singletonList("task"));

        return parameters;
    }

    @Override
    public Object execute(Object input) {
        try {
            Map<String, Object> params = (Map<String, Object>) input;
            String task = (String) params.get("task");
            List<String> fileNames = agentContext.getProductFiles().stream().map(File::getFileName).collect(Collectors.toList());
            CodeInterpreterRequest request = CodeInterpreterRequest.builder()
                    .requestId(agentContext.getSessionId()) // 适配多轮对话
                    .query(agentContext.getQuery())
                    .task(task)
                    .fileNames(fileNames)
                    .stream(true)
                    .build();
            ToolArtifactSource artifactSource = agentContext.requireCurrentToolArtifactSource(getName());

            // 调用流式 API
            CompletableFuture<ToolResultPayload> future = callCodeAgentStream(request, artifactSource);
            return AgentFutureWaiter.await(future, agentContext, Duration.ofSeconds(TOOL_TIMEOUT_SECONDS));
        } catch (AgentFutureWaiter.RunDeadlineExceededException e) {
            cancelActiveStream();
            return buildFailurePayload("code_interpreter 执行失败：Agent 运行时间预算已耗尽。");
        } catch (AgentFutureWaiter.DownstreamAbortedException e) {
            cancelActiveStream();
            return buildFailurePayload("code_interpreter 执行已取消：客户端连接已断开。");
        } catch (TimeoutException e) {
            cancelActiveStream();
            return buildFailurePayload("code_interpreter 执行超时，已取消远端任务。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelActiveStream();
            return buildFailurePayload("code_interpreter 执行被中断，已取消远端任务。");
        } catch (Exception e) {
            log.error("{} code_interpreter execute failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            return buildFailurePayload("code_interpreter 执行失败：" + e.getMessage());
        } finally {
            activeStreamSession = null;
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
            String url = reactorConfig.getCodeInterpreterUrl() + "/v1/tool/code_interpreter";
            log.info("{} code_interpreter request started fileCount={} stream={} contentStream={}",
                    agentContext.getRequestId(),
                    codeRequest.getFileNames() == null ? 0 : codeRequest.getFileNames().size(),
                    codeRequest.getStream(), codeRequest.getContentStream());
            java.util.concurrent.atomic.AtomicReference<CodeInterpreterResponse> latestResponseRef =
                    new java.util.concurrent.atomic.AtomicReference<>(CodeInterpreterResponse.builder()
                            .codeOutput("code_interpreter执行失败")
                            .build());

            activeStreamSession = requireRemoteStreamPort().openStream(RemoteStreamRequest.builder()
                    .method("POST")
                    .url(url)
                    .headers(ReactorToolRequestHeaders.json(reactorConfig))
                    .body(JsonUtils.toJson(codeRequest))
                    .connectTimeoutSeconds(CONNECT_TIMEOUT_SECONDS)
                    .readTimeoutSeconds(READ_TIMEOUT_SECONDS)
                    .writeTimeoutSeconds(WRITE_TIMEOUT_SECONDS)
                    .callTimeoutSeconds(TOOL_TIMEOUT_SECONDS)
                    .build(), new RemoteStreamListener() {
                @Override
                public void onOpen() {
                    log.info("{} code_interpreter stream opened", agentContext.getRequestId());
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
                    log.debug("{} code_interpreter event received payloadChars={}",
                            agentContext.getRequestId(), data.length());
                    CodeInterpreterResponse codeResponse = JsonUtils.parseObject(data, CodeInterpreterResponse.class);
                    latestResponseRef.set(codeResponse);
                    if (Objects.nonNull(codeResponse.getFileInfo()) && !codeResponse.getFileInfo().isEmpty()) {
                        for (CodeInterpreterResponse.FileInfo fileInfo : codeResponse.getFileInfo()) {
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
                    log.debug("{} code_interpreter event projected tool={} fileCount={}",
                            agentContext.getRequestId(), getName(),
                            codeResponse.getFileInfo() == null ? 0 : codeResponse.getFileInfo().size());
                    agentContext.getPrinter().send(new AgentStreamEvent.StageOutput(
                            agentContext.getRequestId(), artifactSource.getToolCallId(), "code",
                            codeResponse,
                            toArtifactRefs(agentContext.getArtifactBindingsByToolCallId(artifactSource.getToolCallId())),
                            Boolean.TRUE.equals(codeResponse.getIsFinal())));
                }

                @Override
                public void onClosed() {
                    CodeInterpreterResponse codeResponse = latestResponseRef.get();
                    StringBuilder output = new StringBuilder(StringUtils.defaultString(codeResponse.getCodeOutput()));
                    if (Objects.nonNull(codeResponse.getFileInfo()) && !codeResponse.getFileInfo().isEmpty()) {
                        output.append("\n\n其中保存了文件: ");
                        for (CodeInterpreterResponse.FileInfo fileInfo : codeResponse.getFileInfo()) {
                            output.append(fileInfo.getFileName()).append("\n");
                        }
                    }
                    if (!future.isDone()) {
                        future.complete(buildSuccessPayload(codeResponse, output.toString()));
                    }
                }

                @Override
                public void onFailure(Throwable throwable, Integer statusCode, String responseBody) {
                    log.error("{} code_interpreter upstream failed statusCode={} responseChars={} errorType={}",
                            agentContext.getRequestId(), statusCode,
                            responseBody == null ? 0 : responseBody.length(),
                            throwable == null ? "unknown" : throwable.getClass().getSimpleName());
                    if (!future.isDone()) {
                        if (statusCode != null) {
                            future.complete(buildFailurePayload("code_interpreter 执行失败：上游服务返回异常状态 " + statusCode + "。"));
                        } else {
                            future.complete(buildFailurePayload("code_interpreter 执行失败：" + throwable.getMessage()));
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("{} code_interpreter request failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            future.complete(buildFailurePayload("code_interpreter 执行失败：" + e.getMessage()));
        }

        return future;
    }

    private void cancelActiveStream() {
        RemoteStreamSession session = activeStreamSession;
        if (session != null) {
            session.cancel();
        }
    }

    private ToolResultPayload buildSuccessPayload(CodeInterpreterResponse codeResponse, String displayText) {
        return ToolResultPayload.structured(
                displayText,
                displayText,
                CodeInterpreterToolOutput.builder()
                        .codeOutput(codeResponse == null ? null : codeResponse.getCodeOutput())
                        .content(codeResponse == null ? null : codeResponse.getContent())
                        .code(codeResponse == null ? null : codeResponse.getCode())
                        .explain(codeResponse == null ? null : codeResponse.getExplain())
                        .fileRefs(ToolFileRefMapper.fromCodeInterpreterFileInfo(codeResponse == null ? null : codeResponse.getFileInfo()))
                        .build()
        );
    }

    private ToolResultPayload buildFailurePayload(String message) {
        return ToolResultPayload.failure(
                message,
                message,
                CodeInterpreterToolOutput.builder()
                        .codeOutput(message)
                        .build(),
                message
        );
    }

    private ReactorConfig requireReactorConfig() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("CodeInterpreterTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireReactorConfig();
    }

    private RemoteStreamPort requireRemoteStreamPort() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("CodeInterpreterTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireRemoteStreamPort();
    }
}
