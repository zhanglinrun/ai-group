package com.linrun.agent.domain.agent.ledger;

import com.linrun.agent.domain.agent.ledger.model.ArtifactRecordCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunLeaseRenewalResult;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunCancelResult;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * 执行账本统一写入契约。
 */
public interface AgentExecutionRecorder {

    /**
     * Atomically claims {@code dialogue_run.request_id}.
     * Only a NEW result authorizes model or tool execution.
     */
    DialogueRunClaim claimRun(DialogueRunStartRecord record);

    Long createRun(DialogueRunStartRecord record);

    void finishRun(DialogueRunFinishRecord record);

    /** Returns false when the claimed run is no longer active. */
    boolean heartbeatRun(Long runId, String requestId, LocalDateTime heartbeatAt);

    /** P30 owner/fence CAS heartbeat. Any non-active result must stop new side effects. */
    default DialogueRunLeaseRenewalResult renewRunLease(Long runId,
                                                        String requestId,
                                                        String ownerWorkerId,
                                                        long fencingToken,
                                                        LocalDateTime heartbeatAt,
                                                        LocalDateTime leaseExpiresAt) {
        return new DialogueRunLeaseRenewalResult(DialogueRunLeaseRenewalResult.Status.OWNERSHIP_LOST);
    }

    /** Idempotent explicit cancellation; only the run owner may create the durable intent. */
    default DialogueRunCancelResult requestRunCancellation(Long runId, String ownerId, LocalDateTime requestedAt) {
        return new DialogueRunCancelResult(DialogueRunCancelResult.Status.NOT_FOUND, runId, null);
    }

    Long createLlmInvocation(LlmInvocationStartRecord record);

    void finishLlmInvocation(LlmInvocationFinishRecord record);

    Map<String, Long> createToolInvocations(ToolInvocationBatchStartRecord record);

    void finishToolInvocation(ToolInvocationFinishRecord record);

    void recordArtifacts(List<ArtifactRecordCommand> records);

    void recordArtifactsOrThrow(List<ArtifactRecordCommand> records);
}
