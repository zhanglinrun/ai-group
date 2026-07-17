package com.linrun.agent.domain.agent.ledger;

import com.linrun.agent.domain.agent.ledger.entity.ArtifactRecord;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.entity.LlmInvocation;
import com.linrun.agent.domain.agent.ledger.entity.ToolInvocation;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunRecoveryCommand;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 1 执行账本写仓储端口。
 * 仅暴露当前 Recorder 所需的领域级写操作，屏蔽底层 DAO 细节。
 */
public interface IExecutionLedgerWriteRepository {

    /**
     * Attempts the unique-key insert used by durable run claiming.
     * Returns false only for a duplicate key; all other persistence failures propagate.
     */
    boolean insertRunIfAbsent(DialogueRun run);

    DialogueSession querySessionBySessionId(String sessionId);

    DialogueRun queryRunByRequestId(String requestId);

    List<LlmInvocation> queryLlmInvocationsByRunId(Long runId);

    List<ToolInvocation> queryToolInvocationsByRunId(Long runId);

    List<ArtifactRecord> queryArtifactsByRunId(Long runId);

    int updateRunFinish(DialogueRun run);

    int updateRunHeartbeat(Long runId, String requestId, LocalDateTime heartbeatAt);

    int failWorkerLostRuns(DialogueRunRecoveryCommand command);

    void upsertSession(DialogueSessionUpsertRecord record);

    int softDeleteSession(String ownerId, String sessionId);

    List<com.linrun.agent.domain.agent.ledger.model.DialogueRunView> queryRunsBySessionId(String sessionId);

    void insertLlmInvocation(LlmInvocation invocation);

    void updateLlmInvocationFinish(LlmInvocation invocation);

    void insertToolInvocation(ToolInvocation invocation);

    void updateToolInvocationFinish(ToolInvocation invocation);

    int batchInsertArtifacts(List<ArtifactRecord> records);
}
