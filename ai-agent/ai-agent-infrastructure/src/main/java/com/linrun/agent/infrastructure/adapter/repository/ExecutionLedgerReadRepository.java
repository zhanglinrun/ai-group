package com.linrun.agent.infrastructure.adapter.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerReadRepository;
import com.linrun.agent.domain.agent.ledger.entity.ArtifactRecord;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.entity.LlmInvocation;
import com.linrun.agent.domain.agent.ledger.entity.ToolInvocation;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionView;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.infrastructure.dao.reactor.IArtifactLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueRunLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueSessionLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.ILlmInvocationLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IToolInvocationLedgerDao;

import java.util.List;

/**
 * Phase 1 执行账本读仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class ExecutionLedgerReadRepository implements IExecutionLedgerReadRepository {

    private final IDialogueRunLedgerDao dialogueRunLedgerDao;
    private final IDialogueSessionLedgerDao dialogueSessionLedgerDao;
    private final ILlmInvocationLedgerDao llmInvocationLedgerDao;
    private final IToolInvocationLedgerDao toolInvocationLedgerDao;
    private final IArtifactLedgerDao artifactLedgerDao;

    @Override
    public DialogueRun queryRunByRequestId(String requestId) {
        return dialogueRunLedgerDao.queryByRequestId(requestId);
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
    public List<ToolInvocationView> queryRecentToolInvocations(String toolName, int limit) {
        return toolInvocationLedgerDao.queryRecentByToolName(toolName, limit);
    }

    @Override
    public List<DialogueRunView> queryRecentRunsBySessionId(String sessionId, int limit) {
        return dialogueRunLedgerDao.queryRecentBySessionId(sessionId, limit);
    }

    @Override
    public List<DialogueRunView> queryRunsBySessionId(String sessionId) {
        return dialogueRunLedgerDao.queryBySessionId(sessionId);
    }

    @Override
    public DialogueSession querySessionEntity(String sessionId) {
        return dialogueSessionLedgerDao.queryBySessionId(sessionId);
    }

    @Override
    public DialogueSessionView querySession(String sessionId) {
        return dialogueSessionLedgerDao.querySessionView(sessionId);
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(int limit) {
        return dialogueSessionLedgerDao.queryRecentSessions(limit);
    }

    @Override
    public DialogueSessionView querySession(String ownerId, String sessionId) {
        return dialogueSessionLedgerDao.querySessionViewByOwner(ownerId, sessionId);
    }

    @Override
    public List<DialogueSessionView> queryRecentSessions(String ownerId, int limit) {
        return dialogueSessionLedgerDao.queryRecentSessionsByOwner(ownerId, limit);
    }

    @Override
    public List<ArtifactRecord> queryArtifactsByRunIds(List<Long> runIds) {
        return artifactLedgerDao.queryByRunIds(runIds);
    }
}
