package com.linrun.agent.infrastructure.adapter.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerWriteRepository;
import com.linrun.agent.domain.agent.ledger.entity.ArtifactRecord;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.entity.LlmInvocation;
import com.linrun.agent.domain.agent.ledger.entity.ToolInvocation;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunRecoveryCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunLeaseRenewalCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunCancelCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import com.linrun.agent.infrastructure.dao.reactor.IArtifactLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueRunLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueSessionLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.ILlmInvocationLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IToolInvocationLedgerDao;

import java.util.List;
import java.time.LocalDateTime;

/**
 * Phase 1 执行账本写仓储适配器。
 * 继续复用现有 DAO / entity / mapper XML，只把持久化细节封在 infrastructure。
 */
@Repository
@RequiredArgsConstructor
public class ExecutionLedgerWriteRepository implements IExecutionLedgerWriteRepository {

    private final IDialogueRunLedgerDao dialogueRunLedgerDao;
    private final IDialogueSessionLedgerDao dialogueSessionLedgerDao;
    private final ILlmInvocationLedgerDao llmInvocationLedgerDao;
    private final IToolInvocationLedgerDao toolInvocationLedgerDao;
    private final IArtifactLedgerDao artifactLedgerDao;

    @Override
    public boolean insertRunIfAbsent(DialogueRun run) {
        try {
            return dialogueRunLedgerDao.insertRun(run) == 1;
        } catch (DuplicateKeyException duplicateKeyException) {
            return false;
        }
    }

    @Override
    public DialogueSession querySessionBySessionId(String sessionId) {
        return dialogueSessionLedgerDao.queryBySessionId(sessionId);
    }

    @Override
    public DialogueRun queryRunByRequestId(String requestId) {
        return dialogueRunLedgerDao.queryByRequestId(requestId);
    }

    @Override
    public DialogueRun queryRunById(Long runId) {
        return dialogueRunLedgerDao.queryById(runId);
    }

    @Override
    public List<LlmInvocation> queryLlmInvocationsByRunId(Long runId) {
        return llmInvocationLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<ToolInvocation> queryToolInvocationsByRunId(Long runId) {
        return toolInvocationLedgerDao.queryByRunId(runId);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunId(Long runId) {
        return artifactLedgerDao.queryByRunId(runId);
    }

    @Override
    public int updateRunFinish(DialogueRun run) {
        return dialogueRunLedgerDao.updateRunFinish(run);
    }

    @Override
    public int updateRunHeartbeat(Long runId, String requestId, LocalDateTime heartbeatAt) {
        return dialogueRunLedgerDao.updateRunHeartbeat(runId, requestId, heartbeatAt);
    }

    @Override
    public int renewRunLease(DialogueRunLeaseRenewalCommand command) {
        return dialogueRunLedgerDao.renewRunLease(command);
    }

    @Override
    public int requestRunCancellation(DialogueRunCancelCommand command) {
        return dialogueRunLedgerDao.requestRunCancellation(command);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int failWorkerLostRuns(DialogueRunRecoveryCommand command) {
        int recovered = dialogueRunLedgerDao.failWorkerLostRuns(command);
        // A dialogue_run terminal CAS and its denormalized dialogue_session head
        // form one recovery unit. Otherwise the history list can remain RUNNING
        // forever even though replay already observes RUN_WORKER_LOST.
        if (recovered > 0) {
            dialogueSessionLedgerDao.reconcileWorkerLostSessionHeads(command.errorCode());
        }
        return recovered;
    }

    @Override
    public void upsertSession(DialogueSessionUpsertRecord record) {
        dialogueSessionLedgerDao.upsertSession(record);
    }

    @Override
    public int softDeleteSession(String ownerId, String sessionId) {
        return dialogueSessionLedgerDao.softDeleteSession(ownerId, sessionId);
    }

    @Override
    public List<DialogueRunView> queryRunsBySessionId(String sessionId) {
        return dialogueRunLedgerDao.queryBySessionId(sessionId);
    }

    @Override
    public void insertLlmInvocation(LlmInvocation invocation) {
        llmInvocationLedgerDao.insertLlmInvocation(invocation);
    }

    @Override
    public void updateLlmInvocationFinish(LlmInvocation invocation) {
        llmInvocationLedgerDao.updateLlmInvocationFinish(invocation);
    }

    @Override
    public void insertToolInvocation(ToolInvocation invocation) {
        toolInvocationLedgerDao.insertToolInvocation(invocation);
    }

    @Override
    public void updateToolInvocationFinish(ToolInvocation invocation) {
        toolInvocationLedgerDao.updateToolInvocationFinish(invocation);
    }

    @Override
    public int batchInsertArtifacts(List<ArtifactRecord> records) {
        return artifactLedgerDao.batchInsertArtifacts(records);
    }
}
