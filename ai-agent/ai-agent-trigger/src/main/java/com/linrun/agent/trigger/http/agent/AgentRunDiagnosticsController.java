package com.linrun.agent.trigger.http.agent;

import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.model.ArtifactView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionRunDetail;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationView;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.domain.agent.service.session.SessionOwnershipDeniedException;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only, owner-scoped run diagnostics with prompt/tool payloads redacted. */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
public class AgentRunDiagnosticsController {

    private final ExecutionLedgerQueryService executionLedgerQueryService;

    @GetMapping("/{requestId}/diagnostics")
    public Response<RunDiagnosticsView> diagnostics(@PathVariable String requestId) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        ExecutionRunDetail detail = executionLedgerQueryService.queryRunDetail(requestId);
        if (detail == null || detail.getRun() == null || !StringUtils.equals(ownerId, detail.getRun().getOwnerId())) {
            throw new SessionOwnershipDeniedException("当前用户无权访问该运行记录");
        }
        return Response.<RunDiagnosticsView>builder()
                .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(RunDiagnosticsView.from(detail)).build();
    }

    public record RunDiagnosticsView(String requestId, String sessionId, String entryAgent, Integer status,
                                     String errorCode, Long durationMs, Integer totalTokens,
                                     List<ModelInvocationView> modelInvocations,
                                     List<ToolInvocationDiagnosticView> toolInvocations,
                                     List<ArtifactDiagnosticView> artifacts) {
        static RunDiagnosticsView from(ExecutionRunDetail detail) {
            return new RunDiagnosticsView(detail.getRun().getRequestId(), detail.getRun().getSessionId(),
                    detail.getRun().getEntryAgent(), detail.getRun().getStatus(), detail.getRun().getErrorCode(),
                    detail.getRun().getDurationMs(), detail.getRun().getTotalTokensTotal(),
                    safe(detail.getLlmInvocations()).stream().map(ModelInvocationView::from).toList(),
                    safe(detail.getToolInvocations()).stream().map(ToolInvocationDiagnosticView::from).toList(),
                    safe(detail.getArtifacts()).stream().map(ArtifactDiagnosticView::from).toList());
        }
    }

    public record ModelInvocationView(Long id, String callKind, String modelName, String costOwner,
                                      String promptHash, String modelParametersJson, String toolSnapshotJson,
                                      String skillSnapshotJson, String configHash, Integer promptTokens,
                                      Integer completionTokens, Integer totalTokens, Long chargedMicrocredits,
                                      String usageSource, Integer status, String errorCode, Long durationMs,
                                      Long providerLatencyMs) {
        static ModelInvocationView from(LlmInvocationView value) {
            return new ModelInvocationView(value.getId(), value.getCallKind(), value.getModelName(),
                    value.getCostOwner(), value.getPromptHash(), value.getModelParametersJson(),
                    value.getToolSnapshotJson(), value.getSkillSnapshotJson(), value.getConfigHash(),
                    value.getPromptTokens(), value.getCompletionTokens(), value.getTotalTokens(),
                    value.getChargedMicrocredits(), value.getUsageSource(), value.getStatus(),
                    value.getErrorMsg(), value.getDurationMs(), value.getProviderLatencyMs());
        }
    }

    public record ToolInvocationDiagnosticView(String toolCallId, String toolName, String toolProvider,
                                               Integer status, String errorCode, Long durationMs,
                                               Integer artifactCount) {
        static ToolInvocationDiagnosticView from(ToolInvocationView value) {
            return new ToolInvocationDiagnosticView(value.getToolCallId(), value.getToolName(),
                    value.getToolProvider(), value.getStatus(), value.getErrorMsg(), value.getDurationMs(),
                    value.getArtifactCount());
        }
    }

    public record ArtifactDiagnosticView(String fileName, String artifactRole, String mimeType,
                                         Long fileSize, String sourceType, LocalDateTime createdAt) {
        static ArtifactDiagnosticView from(ArtifactView value) {
            return new ArtifactDiagnosticView(value.getFileName(), value.getArtifactRole(), value.getMimeType(),
                    value.getFileSize(), value.getSourceType(), value.getCreateTime());
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
