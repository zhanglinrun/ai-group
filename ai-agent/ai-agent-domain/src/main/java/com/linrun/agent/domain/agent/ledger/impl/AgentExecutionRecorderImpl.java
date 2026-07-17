package com.linrun.agent.domain.agent.ledger.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerWriteRepository;
import com.linrun.agent.domain.agent.ledger.entity.ArtifactRecord;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.entity.LlmInvocation;
import com.linrun.agent.domain.agent.ledger.entity.ToolInvocation;
import com.linrun.agent.domain.agent.ledger.model.ArtifactRecordCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputNames;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.tooloutput.ToolOutputWriter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 执行账本写入服务。
 * 普通观测写入维持 fail-open；作为执行互斥边界的 run claim 必须 fail-closed。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionRecorderImpl implements AgentExecutionRecorder {

    private static final int RUN_FINISH_MAX_ATTEMPTS = 3;
    private static final long RUN_FINISH_RETRY_DELAY_MILLIS = 25L;

    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;
    private final ToolOutputWriter toolOutputWriter;

    private final Map<String, LongAdder> successCounters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failureCounters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> durationTotals = new ConcurrentHashMap<>();

    @Override
    public DialogueRunClaim claimRun(DialogueRunStartRecord record) {
        if (record == null || StringUtils.isBlank(record.getRequestId())) {
            throw new IllegalArgumentException("requestId must not be blank when claiming a dialogue run");
        }
        LocalDateTime startedAt = defaultNow(record.getStartedAt());
        DialogueRun entity = DialogueRun.builder()
                .runUid(StringUtils.defaultIfBlank(record.getRunUid(), record.getRequestId()))
                .requestId(record.getRequestId())
                .sessionId(record.getSessionId())
                .ownerId(record.getOwnerId())
                .entryAgent(record.getEntryAgent())
                .roleAgentId(record.getRoleAgentId())
                .roleAgentName(record.getRoleAgentName())
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .queryText(record.getQueryText())
                .requestFingerprint(record.getRequestFingerprint())
                .llmCallCount(0)
                .toolCallCount(0)
                .artifactCount(0)
                .promptTokensTotal(0)
                .completionTokensTotal(0)
                .totalTokensTotal(0)
                .startedAt(startedAt)
                .deadlineAt(record.getDeadlineAt())
                .heartbeatAt(defaultNow(record.getHeartbeatAt()))
                .build();

        final boolean inserted;
        try {
            inserted = executionLedgerWriteRepository.insertRunIfAbsent(entity);
        } catch (Exception error) {
            markFailure("claimRun", record.getRequestId(), null, null, error);
            throw error;
        }

        if (inserted) {
            if (entity.getId() == null) {
                IllegalStateException error = new IllegalStateException(
                        "dialogue run claim inserted without a generated id: " + record.getRequestId());
                markFailure("claimRun", record.getRequestId(), null, null, error);
                throw error;
            }
            // The unique dialogue_run row is the durable claim. Session-head refresh is
            // secondary metadata and must never invalidate an already acquired claim.
            try {
                upsertSessionHead(DialogueSessionUpsertRecord.builder()
                        .sessionId(record.getSessionId())
                        .ownerId(record.getOwnerId())
                        .title(resolveSessionTitle(record.getQueryText()))
                        .status(ExecutionLedgerConstants.STATUS_RUNNING)
                        .latestRequestId(record.getRequestId())
                        .latestQueryText(record.getQueryText())
                        .latestSummaryText(null)
                        .runCount(queryRunCount(record.getSessionId()))
                        .finishedRunCount(queryFinishedRunCount(record.getSessionId()))
                        .failedRunCount(queryFailedRunCount(record.getSessionId()))
                        .startedAt(resolveSessionStartedAt(record.getSessionId(), startedAt))
                        .lastActiveAt(startedAt)
                        .build());
            } catch (Exception sessionError) {
                markFailure("claimRunSessionHead", record.getRequestId(), entity.getId(), null, sessionError);
            }
            markSuccess("claimRun", null);
            return toClaim(DialogueRunClaim.Disposition.NEW, entity);
        }

        final DialogueRun existing;
        try {
            existing = executionLedgerWriteRepository.queryRunByRequestId(record.getRequestId());
        } catch (Exception queryError) {
            markFailure("claimRunLookup", record.getRequestId(), null, null, queryError);
            throw queryError;
        }
        if (existing == null) {
            IllegalStateException error = new IllegalStateException(
                    "dialogue run unique-key conflict could not be resolved: " + record.getRequestId());
            markFailure("claimRunLookup", record.getRequestId(), null, null, error);
            throw error;
        }
        if (!StringUtils.equals(existing.getOwnerId(), record.getOwnerId())) {
            markSuccess("claimRunOwnerMismatch", null);
            return toClaim(DialogueRunClaim.Disposition.OWNER_MISMATCH, existing);
        }
        if (!StringUtils.equals(existing.getSessionId(), record.getSessionId())) {
            markSuccess("claimRunRequestMismatch", null);
            return toClaim(DialogueRunClaim.Disposition.REQUEST_MISMATCH, existing);
        }
        if (StringUtils.isNotBlank(existing.getRequestFingerprint())
                && !StringUtils.equals(existing.getRequestFingerprint(), record.getRequestFingerprint())) {
            markSuccess("claimRunRequestMismatch", null);
            return toClaim(DialogueRunClaim.Disposition.REQUEST_MISMATCH, existing);
        }

        DialogueRunClaim.Disposition disposition = existing.getStatus() == null
                || existing.getStatus() == ExecutionLedgerConstants.STATUS_RUNNING
                ? DialogueRunClaim.Disposition.RUNNING
                : DialogueRunClaim.Disposition.FINISHED;
        markSuccess("claimRun" + disposition.name(), null);
        return toClaim(disposition, existing);
    }

    /**
     * Compatibility API for fixture setup. Duplicate creation is fail-closed;
     * idempotent runtime callers must consume {@link #claimRun(DialogueRunStartRecord)}.
     */
    @Override
    public Long createRun(DialogueRunStartRecord record) {
        DialogueRunClaim claim = claimRun(record);
        if (!claim.isNew()) {
            throw new IllegalStateException("dialogue run already claimed, requestId="
                    + claim.getRequestId() + ", disposition=" + claim.getDisposition());
        }
        return claim.getRunId();
    }

    private DialogueRunClaim toClaim(DialogueRunClaim.Disposition disposition, DialogueRun run) {
        return DialogueRunClaim.builder()
                .disposition(disposition)
                .runId(run.getId())
                .runUid(run.getRunUid())
                .requestId(run.getRequestId())
                .ownerId(run.getOwnerId())
                .sessionId(run.getSessionId())
                .runStatus(run.getStatus())
                .finalSummaryText(run.getFinalSummaryText())
                .errorCode(run.getErrorCode())
                .errorMsg(run.getErrorMsg())
                .build();
    }

    @Override
    public void finishRun(DialogueRunFinishRecord record) {
        if (record == null || record.getRunId() == null || StringUtils.isBlank(record.getRequestId())
                || record.getStatus() == null
                || record.getStatus() == ExecutionLedgerConstants.STATUS_RUNNING) {
            throw new IllegalArgumentException("runId, requestId and a terminal status are required when finishing a dialogue run");
        }
        LocalDateTime finishedAt = defaultNow(record.getFinishedAt());
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= RUN_FINISH_MAX_ATTEMPTS; attempt++) {
            try {
                DialogueRun existing = finishRunOnce(record, finishedAt);
                refreshSessionHeadBestEffort(existing, record, finishedAt);
                markSuccess("finishRun", calculateDuration(existing.getStartedAt(), finishedAt));
                return;
            } catch (Exception error) {
                lastFailure = error;
                if (attempt < RUN_FINISH_MAX_ATTEMPTS) {
                    log.warn("Execution ledger finishRun retry scheduled, requestId={}, runId={}, attempt={}",
                            record.getRequestId(), record.getRunId(), attempt);
                    pauseBeforeRunFinishRetry(attempt, record, error);
                }
            }
        }
        IllegalStateException terminalFailure = new IllegalStateException(
                "failed to persist terminal dialogue run after " + RUN_FINISH_MAX_ATTEMPTS
                        + " attempts, requestId=" + record.getRequestId(),
                lastFailure);
        markFailure("finishRun", record.getRequestId(), record.getRunId(), null, terminalFailure);
        throw terminalFailure;
    }

    @Override
    public boolean heartbeatRun(Long runId, String requestId, LocalDateTime heartbeatAt) {
        if (runId == null || StringUtils.isBlank(requestId)) {
            throw new IllegalArgumentException("runId and requestId are required for dialogue run heartbeat");
        }
        try {
            int updated = executionLedgerWriteRepository.updateRunHeartbeat(
                    runId, requestId, defaultNow(heartbeatAt));
            markSuccess("heartbeatRun", null);
            return updated == 1;
        } catch (Exception error) {
            markFailure("heartbeatRun", requestId, runId, null, error);
            throw error;
        }
    }

    private DialogueRun finishRunOnce(DialogueRunFinishRecord record, LocalDateTime finishedAt) {
        DialogueRun existing = executionLedgerWriteRepository.queryRunByRequestId(record.getRequestId());
        if (existing == null) {
            throw new IllegalStateException("dialogue run not found while finishing requestId=" + record.getRequestId());
        }
        if (!record.getRunId().equals(existing.getId())) {
            throw new IllegalStateException("dialogue run id mismatch while finishing requestId=" + record.getRequestId());
        }
        if (existing.getStatus() != null && existing.getStatus() != ExecutionLedgerConstants.STATUS_RUNNING) {
            if (existing.getStatus().equals(record.getStatus())) {
                return existing;
            }
            throw new IllegalStateException("dialogue run already has a different terminal status, requestId="
                    + record.getRequestId());
        }

        List<LlmInvocation> llmInvocations = queryLlmInvocationsForFinish(existing, record);
        List<ToolInvocation> toolInvocations = queryToolInvocationsForFinish(existing, record);
        List<ArtifactRecord> artifacts = queryArtifactsForFinish(existing, record);
        DialogueRun updateEntity = DialogueRun.builder()
                .id(existing.getId())
                .requestId(record.getRequestId())
                .status(record.getStatus())
                .finalSummaryText(record.getFinalSummaryText())
                .llmCallCount(llmInvocations == null ? defaultZero(existing.getLlmCallCount()) : sizeOf(llmInvocations))
                .toolCallCount(toolInvocations == null ? defaultZero(existing.getToolCallCount()) : sizeOf(toolInvocations))
                .artifactCount(artifacts == null ? defaultZero(existing.getArtifactCount()) : sizeOf(artifacts))
                .promptTokensTotal(llmInvocations == null
                        ? defaultZero(existing.getPromptTokensTotal()) : sumPromptTokens(llmInvocations))
                .completionTokensTotal(llmInvocations == null
                        ? defaultZero(existing.getCompletionTokensTotal()) : sumCompletionTokens(llmInvocations))
                .totalTokensTotal(llmInvocations == null
                        ? defaultZero(existing.getTotalTokensTotal()) : sumTotalTokens(llmInvocations))
                .errorCode(record.getErrorCode())
                .errorMsg(trimText(record.getErrorMsg(), 2000))
                .finishedAt(finishedAt)
                .durationMs(calculateDuration(existing.getStartedAt(), finishedAt))
                .build();
        if (executionLedgerWriteRepository.updateRunFinish(updateEntity) != 1) {
            throw new IllegalStateException("dialogue run terminal compare-and-set failed, requestId="
                    + record.getRequestId());
        }
        existing.setStatus(record.getStatus());
        existing.setFinalSummaryText(record.getFinalSummaryText());
        existing.setFinishedAt(finishedAt);
        return existing;
    }

    private List<LlmInvocation> queryLlmInvocationsForFinish(DialogueRun existing,
                                                              DialogueRunFinishRecord record) {
        try {
            return executionLedgerWriteRepository.queryLlmInvocationsByRunId(existing.getId());
        } catch (Exception metricError) {
            markFailure("finishRunLlmMetrics", record.getRequestId(), record.getRunId(), null, metricError);
            return null;
        }
    }

    private List<ToolInvocation> queryToolInvocationsForFinish(DialogueRun existing,
                                                                DialogueRunFinishRecord record) {
        try {
            return executionLedgerWriteRepository.queryToolInvocationsByRunId(existing.getId());
        } catch (Exception metricError) {
            markFailure("finishRunToolMetrics", record.getRequestId(), record.getRunId(), null, metricError);
            return null;
        }
    }

    private List<ArtifactRecord> queryArtifactsForFinish(DialogueRun existing,
                                                          DialogueRunFinishRecord record) {
        try {
            return executionLedgerWriteRepository.queryArtifactsByRunId(existing.getId());
        } catch (Exception metricError) {
            markFailure("finishRunArtifactMetrics", record.getRequestId(), record.getRunId(), null, metricError);
            return null;
        }
    }

    private void refreshSessionHeadBestEffort(DialogueRun existing,
                                              DialogueRunFinishRecord record,
                                              LocalDateTime finishedAt) {
        try {
            upsertSessionHead(DialogueSessionUpsertRecord.builder()
                    .sessionId(existing.getSessionId())
                    .ownerId(existing.getOwnerId())
                    .title(resolveSessionTitle(existing.getQueryText()))
                    .status(existing.getStatus())
                    .latestRequestId(existing.getRequestId())
                    .latestQueryText(existing.getQueryText())
                    .latestSummaryText(existing.getFinalSummaryText())
                    .runCount(queryRunCount(existing.getSessionId()))
                    .finishedRunCount(queryFinishedRunCount(existing.getSessionId()))
                    .failedRunCount(queryFailedRunCount(existing.getSessionId()))
                    .startedAt(resolveSessionStartedAt(existing.getSessionId(), existing.getStartedAt()))
                    .lastActiveAt(finishedAt)
                    .build());
        } catch (Exception sessionError) {
            markFailure("finishRunSessionHead", record.getRequestId(), record.getRunId(), null, sessionError);
        }
    }

    private void pauseBeforeRunFinishRetry(int attempt,
                                           DialogueRunFinishRecord record,
                                           Exception previousFailure) {
        try {
            Thread.sleep(RUN_FINISH_RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException error = new IllegalStateException(
                    "interrupted while retrying dialogue run finalization, requestId=" + record.getRequestId(),
                    previousFailure);
            error.addSuppressed(interrupted);
            throw error;
        }
    }

    @Override
    public Long createLlmInvocation(LlmInvocationStartRecord record) {
        if (record == null || record.getRunId() == null) {
            return null;
        }
        LlmInvocation entity = LlmInvocation.builder()
                .runId(record.getRunId())
                .invocationSeq(record.getInvocationSeq())
                .agentName(record.getAgentName())
                .stepNo(record.getStepNo())
                .callKind(record.getCallKind())
                .streaming(Boolean.TRUE.equals(record.getStreaming()) ? 1 : 0)
                .modelName(record.getModelName())
                .inputRateSnapshot(record.getInputRateSnapshot())
                .outputRateSnapshot(record.getOutputRateSnapshot())
                .toolCallCount(0)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .status(ExecutionLedgerConstants.STATUS_RUNNING)
                .startedAt(defaultNow(record.getStartedAt()))
                .build();
        try {
            executionLedgerWriteRepository.insertLlmInvocation(entity);
            markSuccess("createLlmInvocation", null);
            return entity.getId();
        } catch (Exception e) {
            markFailure("createLlmInvocation", record.getRequestId(), record.getRunId(), null, e);
            return null;
        }
    }

    @Override
    public void finishLlmInvocation(LlmInvocationFinishRecord record) {
        if (record == null || record.getLlmInvocationId() == null) {
            return;
        }
        try {
            LocalDateTime finishedAt = defaultNow(record.getFinishedAt());
            executionLedgerWriteRepository.updateLlmInvocationFinish(LlmInvocation.builder()
                    .id(record.getLlmInvocationId())
                    .status(record.getStatus())
                    .responseText(record.getResponseText())
                    .toolCallCount(defaultZero(record.getToolCallCount()))
                    .promptTokens(defaultZero(record.getPromptTokens()))
                    .completionTokens(defaultZero(record.getCompletionTokens()))
                    .totalTokens(defaultZero(record.getTotalTokens()))
                    .usageSource(record.getUsageSource())
                    .chargedMicrocredits(record.getChargedMicrocredits())
                    .finishReason(record.getFinishReason())
                    .errorMsg(trimText(record.getErrorMsg(), 2000))
                    .finishedAt(finishedAt)
                    .build());
            markSuccess("finishLlmInvocation", null);
        } catch (Exception e) {
            markFailure("finishLlmInvocation", record.getRequestId(), null, null, e);
        }
    }

    @Override
    public Map<String, Long> createToolInvocations(ToolInvocationBatchStartRecord record) {
        Map<String, Long> mapping = new LinkedHashMap<>();
        if (record == null || record.getRunId() == null || CollectionUtils.isEmpty(record.getItems())) {
            return mapping;
        }
        for (ToolInvocationBatchStartRecord.Item item : record.getItems()) {
            if (item == null || StringUtils.isBlank(item.getToolCallId())) {
                continue;
            }
            ToolInvocation entity = ToolInvocation.builder()
                    .runId(record.getRunId())
                    .llmInvocationId(record.getLlmInvocationId())
                    .toolCallId(item.getToolCallId())
                    .dispatchIndex(item.getDispatchIndex())
                    .agentName(record.getAgentName())
                    .stepNo(record.getStepNo())
                    .toolName(item.getToolName())
                    .toolProvider(item.getToolProvider())
                    .inputJson(item.getInputJson())
                    .status(ExecutionLedgerConstants.STATUS_RUNNING)
                    .startedAt(defaultNow(item.getStartedAt()))
                    .build();
            try {
                executionLedgerWriteRepository.insertToolInvocation(entity);
                mapping.put(item.getToolCallId(), entity.getId());
                markSuccess("createToolInvocation", null);
            } catch (Exception e) {
                markFailure("createToolInvocation", record.getRequestId(), record.getRunId(), item.getToolCallId(), e);
            }
        }
        return mapping;
    }

    @Override
    public void finishToolInvocation(ToolInvocationFinishRecord record) {
        if (record == null || record.getToolInvocationId() == null) {
            return;
        }
        try {
            executionLedgerWriteRepository.updateToolInvocationFinish(ToolInvocation.builder()
                    .id(record.getToolInvocationId())
                    .status(record.getStatus())
                    .toolResult(record.getToolResult())
                    .llmObservation(record.getLlmObservation())
                    .errorMsg(trimText(record.getErrorMsg(), 2000))
                    .finishedAt(defaultNow(record.getFinishedAt()))
                    .build());
            persistStructuredOutput(record);
            markSuccess("finishToolInvocation", null);
        } catch (Exception e) {
            markFailure("finishToolInvocation", record.getRequestId(), null, record.getToolCallId(), e);
        }
    }

    /**
     * rich tool 输出和主账本分离，避免结构化结果继续堆在主表。
     */
    private void persistStructuredOutput(ToolInvocationFinishRecord record) {
        if (toolOutputWriter == null
                || record == null
                || !ToolOutputNames.isRichTool(record.getToolName())
                || record.getStructuredOutput() == null) {
            return;
        }
        toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(record.getToolInvocationId())
                .runId(record.getRunId())
                .requestId(record.getRequestId())
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId(record.getSessionId())
                .toolCallId(record.getToolCallId())
                .toolName(record.getToolName())
                .status(record.getStatus())
                .errorMsg(record.getErrorMsg())
                .structuredOutput(record.getStructuredOutput())
                .build());
    }

    @Override
    public void recordArtifacts(List<ArtifactRecordCommand> records) {
        try {
            recordArtifactsOrThrow(records);
        } catch (Exception e) {
            String requestId = null;
            Long runId = null;
            if (CollectionUtils.isNotEmpty(records)) {
                for (ArtifactRecordCommand record : records) {
                    if (record == null) {
                        continue;
                    }
                    requestId = requestId == null ? record.getRequestId() : requestId;
                    runId = runId == null ? record.getRunId() : runId;
                }
            }
            markFailure("recordArtifacts", requestId, runId, null, e);
        }
    }

    @Override
    public void recordArtifactsOrThrow(List<ArtifactRecordCommand> records) {
        if (CollectionUtils.isEmpty(records)) {
            return;
        }
        List<ArtifactRecord> entities = new ArrayList<>(records.size());
        for (ArtifactRecordCommand record : records) {
            if (record == null || StringUtils.isBlank(record.getFileName())) {
                continue;
            }
            entities.add(ArtifactRecord.builder()
                    .runId(record.getRunId())
                    .requestId(record.getRequestId())
                    .toolInvocationId(record.getToolInvocationId())
                    .toolCallId(record.getToolCallId())
                    .artifactRole(record.getArtifactRole())
                    .visibility(record.getVisibility())
                    .sourceType(record.getSourceType())
                    .sourceName(record.getSourceName())
                    .fileName(record.getFileName())
                    .storageKey(defaultEmpty(record.getStorageKey()))
                    .downloadUrl(record.getDownloadUrl())
                    .previewUrl(record.getPreviewUrl())
                    .mimeType(record.getMimeType())
                    .fileSize(record.getFileSize())
                    .fileHash(record.getFileHash())
                    .metadataJson(record.getMetadataJson())
                    .build());
        }
        if (entities.isEmpty()) {
            return;
        }
        int inserted = executionLedgerWriteRepository.batchInsertArtifacts(entities);
        if (inserted < entities.size()) {
            throw new IllegalStateException(String.format(
                    "artifact duplicate or ignored, expected=%d, inserted=%d", entities.size(), inserted));
        }
        markSuccess("recordArtifacts", null);
    }

    private int sumPromptTokens(List<LlmInvocation> invocations) {
        int total = 0;
        if (invocations == null) {
            return total;
        }
        for (LlmInvocation invocation : invocations) {
            total += defaultZero(invocation == null ? null : invocation.getPromptTokens());
        }
        return total;
    }

    private int sumCompletionTokens(List<LlmInvocation> invocations) {
        int total = 0;
        if (invocations == null) {
            return total;
        }
        for (LlmInvocation invocation : invocations) {
            total += defaultZero(invocation == null ? null : invocation.getCompletionTokens());
        }
        return total;
    }

    private int sumTotalTokens(List<LlmInvocation> invocations) {
        int total = 0;
        if (invocations == null) {
            return total;
        }
        for (LlmInvocation invocation : invocations) {
            total += defaultZero(invocation == null ? null : invocation.getTotalTokens());
        }
        return total;
    }

    private int sizeOf(List<?> items) {
        return items == null ? 0 : items.size();
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String defaultEmpty(String value) {
        return value == null ? "" : value;
    }

    private LocalDateTime defaultNow(LocalDateTime value) {
        return value != null ? value : LocalDateTime.now();
    }

    private Long calculateDuration(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return Duration.between(startedAt, finishedAt).toMillis();
    }

    private String trimText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private void markSuccess(String scene, Long durationMs) {
        successCounters.computeIfAbsent(scene, key -> new LongAdder()).increment();
        if (durationMs != null) {
            durationTotals.computeIfAbsent(scene, key -> new LongAdder()).add(durationMs);
        }
    }

    private void markFailure(String scene, String requestId, Long runId, String toolCallId, Exception e) {
        failureCounters.computeIfAbsent(scene, key -> new LongAdder()).increment();
        long success = successCounters.getOrDefault(scene, new LongAdder()).sum();
        long failure = failureCounters.get(scene).sum();
        double successRate = (success + failure) == 0 ? 1D : (double) success / (success + failure);
        log.error("Execution ledger {} failed, requestId={}, runId={}, toolCallId={}, successRate={}",
                scene, requestId, runId, toolCallId, String.format("%.4f", successRate), e);
    }

    /**
     * 会话主表只承接摘要和排序字段，避免再扫一遍 tool/artifact 明细。
     */
    private void upsertSessionHead(DialogueSessionUpsertRecord record) {
        if (record == null || StringUtils.isBlank(record.getSessionId())) {
            return;
        }
        executionLedgerWriteRepository.upsertSession(record);
    }

    private String resolveSessionTitle(String queryText) {
        String normalized = StringUtils.trimToEmpty(queryText);
        if (normalized.isEmpty()) {
            return "新对话";
        }
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    private int queryRunCount(String sessionId) {
        return executionLedgerWriteRepository.queryRunsBySessionId(sessionId).size();
    }

    private int queryFinishedRunCount(String sessionId) {
        return (int) executionLedgerWriteRepository.queryRunsBySessionId(sessionId).stream()
                .filter(item -> item != null && ExecutionLedgerConstants.STATUS_SUCCESS == defaultZero(item.getStatus()))
                .count();
    }

    private int queryFailedRunCount(String sessionId) {
        return (int) executionLedgerWriteRepository.queryRunsBySessionId(sessionId).stream()
                .filter(item -> item != null && isFailedStatus(item.getStatus()))
                .count();
    }

    private boolean isFailedStatus(Integer status) {
        int normalizedStatus = defaultZero(status);
        return normalizedStatus == ExecutionLedgerConstants.STATUS_FAILED
                || normalizedStatus == ExecutionLedgerConstants.STATUS_TIMEOUT
                || normalizedStatus == ExecutionLedgerConstants.STATUS_STOPPED;
    }

    private LocalDateTime resolveSessionStartedAt(String sessionId, LocalDateTime fallback) {
        List<DialogueRunView> runs = executionLedgerWriteRepository.queryRunsBySessionId(sessionId);
        if (CollectionUtils.isEmpty(runs) || runs.get(0) == null || runs.get(0).getStartedAt() == null) {
            return fallback;
        }
        return runs.get(0).getStartedAt();
    }
}
