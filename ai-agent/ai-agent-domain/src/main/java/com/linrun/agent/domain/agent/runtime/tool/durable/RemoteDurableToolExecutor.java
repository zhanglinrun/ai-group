package com.linrun.agent.domain.agent.runtime.tool.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.CodeInterpreterToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRef;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronous Harness facade over the persistent control plane. The remote
 * worker call is an optimization after an atomic outbox write; a DB poller can
 * wake the same invocation when Kafka or this direct wake-up is unavailable.
 */
public final class RemoteDurableToolExecutor implements DurableToolExecutor {

    private static final long CONNECT_TIMEOUT_SECONDS = 15L;
    private static final long CALL_TIMEOUT_SECONDS = 300L;

    private final DurableToolControlPlane controlPlane;
    private final RemoteHttpPort remoteHttpPort;
    private final ReactorConfig reactorConfig;
    private final ObjectMapper objectMapper;

    public RemoteDurableToolExecutor(DurableToolControlPlane controlPlane,
                                     RemoteHttpPort remoteHttpPort,
                                     ReactorConfig reactorConfig) {
        this(controlPlane, remoteHttpPort, reactorConfig, new ObjectMapper());
    }

    RemoteDurableToolExecutor(DurableToolControlPlane controlPlane,
                              RemoteHttpPort remoteHttpPort,
                              ReactorConfig reactorConfig,
                              ObjectMapper objectMapper) {
        this.controlPlane = controlPlane;
        this.remoteHttpPort = remoteHttpPort;
        this.reactorConfig = reactorConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolResultPayload execute(DurableToolExecutionRequest request) {
        try {
            DurableToolScheduleResult scheduled = controlPlane.schedule(request);
            if (scheduled.isReused()) {
                DurableToolInvocation source = scheduled.getInvocation();
                if (source.getStatus() == DurableToolStatus.SUCCEEDED) {
                    String result = source.getResultPayload() == null ? "{}" : source.getResultPayload();
                    return successfulPayload(request.getToolName(), result,
                            "[Reused durable tool result; remote execution was skipped.]\n" + result);
                }
                return ToolResultPayload.failure(
                        "Durable tool operation is already in progress or requires reconciliation.",
                        "Durable tool operation is already in progress or requires reconciliation.",
                        null,
                        source.getErrorType() == null ? "DURABLE_OPERATION_NOT_REUSABLE" : source.getErrorType());
            }

            String workerId = blank(request.getOwnerWorkerId()) ? "agent-runtime" : request.getOwnerWorkerId();
            DurableToolAttempt attempt = controlPlane.startAttempt(
                    request.getToolInvocationId(), workerId, request.getFencingToken());
            try {
                JsonNode response = objectMapper.readTree(remoteHttpPort.execute(RemoteHttpRequest.builder()
                        .method("POST")
                        .url(resolveWorkerEndpoint(request.getToolName()))
                        .headers(workerHeaders(request))
                        .body(objectMapper.writeValueAsString(workerCommand(request, attempt)))
                        .connectTimeoutSeconds(CONNECT_TIMEOUT_SECONDS)
                        .readTimeoutSeconds(CALL_TIMEOUT_SECONDS)
                        .writeTimeoutSeconds(CALL_TIMEOUT_SECONDS)
                        .callTimeoutSeconds(CALL_TIMEOUT_SECONDS)
                        .build()));
                DurableToolStatus status = parseStatus(response.path("status").asText());
                String result = serializeNode(response.has("result") ? response.get("result") : response.get("data"));
                String errorType = nullableText(response, "errorType");
                DurableToolCallbackResult callbackResult = controlPlane.complete(DurableToolWorkerCallback.builder()
                        .toolInvocationId(request.getToolInvocationId())
                        .attemptNo(attempt.getAttemptNo())
                        .workerId(workerId)
                        .fencingToken(request.getFencingToken())
                        .providerRequestId(nullableText(response, "providerRequestId"))
                        .status(status)
                        .errorType(errorType)
                        .resultPayload(result)
                        .resultHash(nullableText(response, "resultHash"))
                        .occurredAt(Instant.now())
                        .build());
                if (callbackResult != DurableToolCallbackResult.ACCEPTED
                        && callbackResult != DurableToolCallbackResult.DUPLICATE) {
                    return ToolResultPayload.failure("Durable worker callback was rejected.",
                            "Durable worker callback was rejected.", null, callbackResult.name());
                }
                if (status == DurableToolStatus.SUCCEEDED) {
                    return successfulPayload(request.getToolName(), result, result);
                }
                return ToolResultPayload.failure(result == null ? "Durable tool failed." : result,
                        result == null ? "Durable tool failed." : result, null,
                        errorType == null ? status.name() : errorType);
            } catch (IOException remoteError) {
                controlPlane.complete(DurableToolWorkerCallback.builder()
                        .toolInvocationId(request.getToolInvocationId())
                        .attemptNo(attempt.getAttemptNo())
                        .workerId(workerId)
                        .fencingToken(request.getFencingToken())
                        .status(DurableToolStatus.UNKNOWN)
                        .errorType("WORKER_TRANSPORT_UNKNOWN")
                        .occurredAt(Instant.now())
                        .build());
                return ToolResultPayload.failure("Durable worker response is unknown; manual reconciliation is required.",
                        "Durable worker response is unknown; manual reconciliation is required.", null,
                        "WORKER_TRANSPORT_UNKNOWN");
            }
        } catch (Exception error) {
            return ToolResultPayload.failure("Durable tool execution failed.", "Durable tool execution failed.",
                    null, error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> workerCommand(DurableToolExecutionRequest request, DurableToolAttempt attempt) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("invocationId", request.getToolInvocationId());
        command.put("runId", request.getRunId());
        command.put("requestId", request.getRequestId());
        command.put("toolCallId", request.getToolCallId());
        command.put("toolName", request.getToolName());
        command.put("operationKey", request.getOperationKey());
        command.put("attemptNo", attempt.getAttemptNo());
        command.put("fencingToken", request.getFencingToken());
        command.put("input", parseInput(request.getInputJson()));
        return command;
    }

    private Object parseInput(String inputJson) {
        try {
            return objectMapper.readValue(inputJson == null ? "{}" : inputJson, Object.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, String> workerHeaders(DurableToolExecutionRequest request) {
        Map<String, String> headers = new LinkedHashMap<>(ReactorToolRequestHeaders.json(reactorConfig));
        headers.put("X-Request-Id", request.getRequestId());
        headers.put("X-Agent-Run-Id", String.valueOf(request.getRunId()));
        headers.put("X-Fencing-Token", String.valueOf(request.getFencingToken()));
        headers.put("X-Trace-Id", request.getRequestId());
        return headers;
    }

    private String resolveWorkerEndpoint(String toolName) {
        String configuredBase = "deep_search".equals(toolName)
                ? reactorConfig.getDeepSearchUrl()
                : reactorConfig.getCodeInterpreterUrl();
        if (blank(configuredBase)) {
            throw new IllegalStateException("durable worker URL is not configured for " + toolName);
        }
        String normalized = configuredBase.trim().replaceAll("/+$", "");
        int legacyApi = normalized.indexOf("/v1/");
        if (legacyApi >= 0) {
            normalized = normalized.substring(0, legacyApi);
        }
        return normalized + "/internal/runtime/tools/execute";
    }

    private DurableToolStatus parseStatus(String rawStatus) {
        try {
            DurableToolStatus status = DurableToolStatus.valueOf(rawStatus == null ? "FAILED" : rawStatus.trim().toUpperCase());
            return status.isTerminal() ? status : DurableToolStatus.FAILED;
        } catch (Exception ignored) {
            return DurableToolStatus.FAILED;
        }
    }

    private String serializeNode(JsonNode node) throws Exception {
        return node == null || node.isNull() ? "{}" : objectMapper.writeValueAsString(node);
    }

    /**
     * Durable code execution crosses a JSON worker boundary rather than the
     * normal SSE adapter. Rebuild the same strong output contract here so the
     * ToolDispatcher can register the upload-backed artifact in the run ledger.
     */
    private ToolResultPayload successfulPayload(String toolName, String result, String observation) {
        if (!"code_interpreter".equals(toolName)) {
            return ToolResultPayload.structured(result, observation, null);
        }
        return ToolResultPayload.structured(result, observation, codeInterpreterOutput(result));
    }

    private CodeInterpreterToolOutput codeInterpreterOutput(String result) {
        List<ToolFileRef> fileRefs = new ArrayList<>();
        try {
            JsonNode files = objectMapper.readTree(result == null ? "{}" : result).path("fileInfo");
            if (files.isArray()) {
                for (JsonNode file : files) {
                    String fileName = text(file, "fileName");
                    String downloadUrl = firstNonBlank(text(file, "downloadUrl"), text(file, "ossUrl"));
                    String previewUrl = firstNonBlank(text(file, "previewUrl"), text(file, "domainUrl"));
                    if (blank(fileName) || blank(downloadUrl) || blank(previewUrl)) {
                        continue;
                    }
                    fileRefs.add(ToolFileRef.builder()
                            .fileName(fileName)
                            .downloadUrl(downloadUrl)
                            .previewUrl(previewUrl)
                            .ossUrl(firstNonBlank(text(file, "ossUrl"), downloadUrl))
                            .domainUrl(firstNonBlank(text(file, "domainUrl"), previewUrl))
                            .fileSize(file.hasNonNull("fileSize") ? file.path("fileSize").asLong() : null)
                            .mimeType(text(file, "mimeType"))
                            .build());
                }
            }
        } catch (Exception ignored) {
            // A successful durable result remains visible as text, but it
            // cannot claim an artifact without verified URL metadata.
        }
        return CodeInterpreterToolOutput.builder().codeOutput(result).fileRefs(fileRefs).build();
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    private String nullableText(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
