package org.wwz.ai.domain.agent.checkpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.entity.ToolInvocation;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * checkpoint 保存、所有权校验和副作用重放门禁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanCheckpointCoordinator {

    private final PlanCheckpointRepository checkpointRepository;
    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final PlanCheckpointProperties properties;

    /**
     * 正常执行的 checkpoint 是可用性增强，数据库暂不可用时不应让 Agent 主任务失败。
     */
    public Optional<PlanExecutionCheckpoint> save(AgentContext context,
                                                   PlanCheckpointPhase phase,
                                                   int stepIndex,
                                                   PlanCheckpointState state) {
        if (!properties.isEnabled() || context == null || !context.hasActiveLedgerRun()) {
            return Optional.empty();
        }
        try {
            int sequence = context.getAgentRunState().nextCheckpointSequence();
            PlanExecutionCheckpoint saved = checkpointRepository.save(PlanExecutionCheckpoint.builder()
                    .checkpointId(UUID.randomUUID().toString())
                    .runId(context.getAgentRunState().getRunId())
                    .requestId(context.getRequestId())
                    .sessionId(context.getSessionId())
                    .ownerId(context.getOwnerId() == null ? null : String.valueOf(context.getOwnerId()))
                    .sequenceNo(sequence)
                    .phase(phase)
                    .stepIndex(stepIndex)
                    .state(state)
                    .resumable(Boolean.TRUE)
                    .build());
            context.getAgentRunState().setLatestCheckpointId(saved.getCheckpointId());
            return Optional.of(saved);
        } catch (RuntimeException exception) {
            log.warn("{} checkpoint persistence unavailable; execution continues without a new recovery point: {}",
                    context.getRequestId(), exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 在创建 AgentContext 和工具集合之前读取不可变请求快照。这里只做所有权检查，真正的
     * 副作用门禁与原子认领仍在 {@link #resume} 中完成。
     */
    public PlanExecutionCheckpoint inspectForResume(String checkpointId, String ownerId, String sessionId) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Plan checkpoint resume is disabled");
        }
        if (StringUtils.isAnyBlank(checkpointId, ownerId, sessionId)) {
            throw new IllegalArgumentException("checkpointId, ownerId and sessionId are required for resume");
        }
        PlanExecutionCheckpoint checkpoint = checkpointRepository
                .findOwned(checkpointId, ownerId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found or not owned by current session"));
        if (!Boolean.TRUE.equals(checkpoint.getResumable())) {
            throw new IllegalStateException("Checkpoint is no longer resumable: " + checkpointId);
        }
        return checkpoint;
    }

    /**
     * 显式恢复必须 fail-closed：校验 owner/session、检查 checkpoint 后的工具调用，并原子认领。
     */
    public PlanExecutionCheckpoint resume(String checkpointId,
                                          String ownerId,
                                          String sessionId,
                                          String resumedByRequestId,
                                          PlanResumeDecision decision) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Plan checkpoint resume is disabled");
        }
        if (StringUtils.isAnyBlank(checkpointId, ownerId, sessionId, resumedByRequestId)) {
            throw new IllegalArgumentException("checkpointId, ownerId, sessionId and requestId are required for resume");
        }

        PlanExecutionCheckpoint checkpoint = checkpointRepository
                .findOwned(checkpointId, ownerId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found or not owned by current session"));
        if (!Boolean.TRUE.equals(checkpoint.getResumable())) {
            throw new IllegalStateException("Checkpoint is no longer resumable: " + checkpointId);
        }
        if (checkpoint.getResumedByRequestId() != null
                && !resumedByRequestId.equals(checkpoint.getResumedByRequestId())) {
            throw new IllegalStateException("Checkpoint has already been consumed by another request");
        }

        DialogueRun sourceRun = executionLedgerReadRepository.queryRunByRequestId(checkpoint.getRequestId());
        if (sourceRun != null && Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS).equals(sourceRun.getStatus())) {
            throw new IllegalStateException("A successfully completed run cannot be resumed");
        }

        List<String> ambiguousTools = findAmbiguousReplayFacts(checkpoint, sourceRun);
        if (!ambiguousTools.isEmpty() && decision != PlanResumeDecision.RESTART_FROM_CHECKPOINT) {
            throw new PlanResumeApprovalRequiredException(checkpointId, ambiguousTools);
        }

        boolean claimed = checkpointRepository.claimForResume(
                checkpointId, ownerId, sessionId, resumedByRequestId, decision);
        if (!claimed) {
            throw new IllegalStateException("Checkpoint was consumed concurrently: " + checkpointId);
        }
        return checkpointRepository.findOwned(checkpointId, ownerId, sessionId)
                .orElseThrow(() -> new IllegalStateException("Claimed checkpoint disappeared: " + checkpointId));
    }

    public void markRunCompleted(AgentContext context) {
        if (!properties.isEnabled() || context == null || context.getAgentRunState() == null
                || context.getAgentRunState().getRunId() == null) {
            return;
        }
        try {
            checkpointRepository.markRunCompleted(context.getAgentRunState().getRunId());
        } catch (RuntimeException exception) {
            log.warn("{} failed to close completed checkpoints: {}", context.getRequestId(), exception.getMessage());
        }
    }

    public PlanCheckpointProperties properties() {
        return properties;
    }

    private List<String> findAmbiguousReplayFacts(PlanExecutionCheckpoint checkpoint, DialogueRun sourceRun) {
        List<ToolInvocation> invocations = executionLedgerReadRepository
                .queryToolInvocationsByRunId(checkpoint.getRunId());
        Set<String> safeTools = new LinkedHashSet<>(properties.getReplaySafeTools());
        Set<String> ambiguous = new LinkedHashSet<>();
        if (sourceRun != null && Integer.valueOf(ExecutionLedgerConstants.STATUS_RUNNING).equals(sourceRun.getStatus())) {
            ambiguous.add("SOURCE_RUN_STILL_RUNNING");
        }
        if (invocations == null || invocations.isEmpty()) {
            return List.copyOf(ambiguous);
        }
        LocalDateTime checkpointTime = checkpoint.getCreatedAt();
        for (ToolInvocation invocation : invocations) {
            if (invocation == null || StringUtils.isBlank(invocation.getToolName())) {
                continue;
            }
            if (checkpointTime != null && invocation.getStartedAt() != null
                    && invocation.getStartedAt().isBefore(checkpointTime)) {
                continue;
            }
            if (!safeTools.contains(invocation.getToolName())) {
                ambiguous.add(invocation.getToolName());
            }
        }
        return List.copyOf(ambiguous);
    }
}
