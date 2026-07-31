package com.linrun.agent.domain.agent.service.session;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerReadRepository;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerWriteRepository;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;

import java.time.LocalDateTime;

/**
 * 会话归属领域服务。
 */
@Service
@RequiredArgsConstructor
public class ConversationSessionOwnershipService {

    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final IExecutionLedgerWriteRepository executionLedgerWriteRepository;

    /**
     * 首次访问时绑定 session 归属，已有归属时校验是否仍属于当前用户。
     */
    public DialogueSession ensureSessionAccessible(String ownerId, String sessionId, String queryText) {
        return ensureSessionAccessible(ownerId, sessionId, queryText, true);
    }

    /**
     * 只校验既有会话的归属，不允许因为探测或详情请求自动创建空会话。
     */
    public DialogueSession ensureExistingSessionAccessible(String ownerId, String sessionId) {
        return ensureSessionAccessible(ownerId, sessionId, null, false);
    }

    public void deleteSession(String ownerId, String sessionId) {
        ensureExistingSessionAccessible(ownerId, sessionId);
        if (executionLedgerWriteRepository.softDeleteSession(ownerId, sessionId) == 0) {
            throw new SessionOwnershipDeniedException("当前会话不存在或已删除");
        }
    }

    private DialogueSession ensureSessionAccessible(String ownerId,
                                                   String sessionId,
                                                   String queryText,
                                                   boolean allowBindWhenMissing) {
        if (StringUtils.isAnyBlank(ownerId, sessionId)) {
            throw new IllegalArgumentException("ownerId 和 sessionId 不能为空");
        }
        DialogueSession existing = executionLedgerReadRepository.querySessionEntity(sessionId);
        if (existing == null) {
            if (!allowBindWhenMissing) {
                throw new SessionOwnershipDeniedException("当前会话不存在");
            }
            LocalDateTime now = LocalDateTime.now();
            executionLedgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(sessionId)
                    .ownerId(ownerId)
                    .title(resolveSessionTitle(queryText))
                    .status(ExecutionLedgerConstants.STATUS_RUNNING)
                    .runCount(0)
                    .finishedRunCount(0)
                    .failedRunCount(0)
                    .startedAt(now)
                    .lastActiveAt(now)
                    .build());
            return requireBoundOwner(executionLedgerWriteRepository.querySessionBySessionId(sessionId), ownerId);
        }
        if (StringUtils.isBlank(existing.getOwnerId())) {
            executionLedgerWriteRepository.upsertSession(DialogueSessionUpsertRecord.builder()
                    .sessionId(existing.getSessionId())
                    .ownerId(ownerId)
                    .title(StringUtils.defaultIfBlank(existing.getTitle(), resolveSessionTitle(queryText)))
                    .status(existing.getStatus())
                    .latestRequestId(existing.getLatestRequestId())
                    .latestQueryText(existing.getLatestQueryText())
                    .latestSummaryText(existing.getLatestSummaryText())
                    .runCount(existing.getRunCount())
                    .finishedRunCount(existing.getFinishedRunCount())
                    .failedRunCount(existing.getFailedRunCount())
                    .startedAt(existing.getStartedAt())
                    .lastActiveAt(existing.getLastActiveAt())
                    .build());
            return requireBoundOwner(executionLedgerWriteRepository.querySessionBySessionId(sessionId), ownerId);
        }
        if (!StringUtils.equals(existing.getOwnerId(), ownerId)) {
            throw new SessionOwnershipDeniedException("当前用户无权访问该会话");
        }
        return existing;
    }

    private DialogueSession requireBoundOwner(DialogueSession session, String ownerId) {
        if (session == null || !StringUtils.equals(session.getOwnerId(), ownerId)) {
            throw new SessionOwnershipDeniedException("当前用户无权访问该会话");
        }
        return session;
    }

    /**
     * 对话标题与账本 recorder 保持同一套收口逻辑，避免首次绑定与首次 run 的标题规则漂移。
     */
    private String resolveSessionTitle(String queryText) {
        String normalized = StringUtils.trimToEmpty(queryText);
        if (normalized.isEmpty()) {
            return "新对话";
        }
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }
}
