package org.wwz.ai.domain.agent.memory;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.types.agent.config.AgentExecutorNames;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link ConversationMemoryManager} 默认实现：组合中期（会话）与长期（跨会话向量）记忆，
 * 并把长期记忆写入放到独立执行器异步执行，全链路 fail-open。
 */
@Slf4j
@Service
public class ConversationMemoryManagerImpl implements ConversationMemoryManager {

    private static final String LONG_TERM_HEADER = "## 长期记忆（跨会话）";

    private final SessionContextMemoryService sessionContextMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final ReactorConfig reactorConfig;

    @Resource(name = AgentExecutorNames.TASK_EXECUTOR)
    private Executor memoryExecutor;

    public ConversationMemoryManagerImpl(SessionContextMemoryService sessionContextMemoryService,
                                         LongTermMemoryService longTermMemoryService,
                                         ExecutionLedgerQueryService executionLedgerQueryService,
                                         ReactorConfig reactorConfig) {
        this.sessionContextMemoryService = sessionContextMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.executionLedgerQueryService = executionLedgerQueryService;
        this.reactorConfig = reactorConfig;
    }

    @Override
    public String assembleHistoryBlock(MemoryQuery query) {
        if (query == null) {
            return "";
        }
        String medium = safeBuildMedium(query.sessionId(), query.currentRequestId());
        if (!isMemoryEnabled()) {
            return StringUtils.defaultString(medium);
        }
        List<String> longTerm = safeRecall(query);
        if (longTerm.isEmpty()) {
            return StringUtils.defaultString(medium);
        }
        StringBuilder builder = new StringBuilder(LONG_TERM_HEADER).append('\n');
        int index = 0;
        for (String snippet : longTerm) {
            index++;
            builder.append(index).append(". ")
                    .append(snippet.replaceAll("\\s+", " ").trim())
                    .append('\n');
        }
        if (StringUtils.isNotBlank(medium)) {
            builder.append('\n').append(medium);
        }
        return builder.toString().trim();
    }

    @Override
    public void persistTurnAsync(MemoryTurn turn) {
        if (!isLongTermEnabled() || turn == null || StringUtils.isBlank(turn.ownerId())) {
            return;
        }
        Runnable task = () -> {
            try {
                longTermMemoryService.save(resolveAnswerSummary(turn));
            } catch (Exception e) {
                log.warn("persist turn to long-term memory failed, ownerId={}, sessionId={}",
                        turn.ownerId(), turn.sessionId(), e);
            }
        };
        try {
            memoryExecutor.execute(task);
        } catch (RejectedExecutionException rex) {
            log.warn("long-term memory persist rejected by executor, skip. ownerId={}", turn.ownerId());
        }
    }

    private MemoryTurn resolveAnswerSummary(MemoryTurn turn) {
        if (StringUtils.isNotBlank(turn.answerSummary())
                || StringUtils.isBlank(turn.requestId())
                || StringUtils.isBlank(turn.sessionId())) {
            return turn;
        }
        try {
            String summary = executionLedgerQueryService.querySessionRuns(turn.sessionId()).stream()
                    .filter(run -> run != null && StringUtils.equals(run.getRequestId(), turn.requestId()))
                    .map(DialogueRunView::getFinalSummaryText)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .orElse(null);
            if (StringUtils.isNotBlank(summary)) {
                return new MemoryTurn(turn.ownerId(), turn.sessionId(), turn.requestId(), turn.query(), summary);
            }
        } catch (Exception e) {
            log.warn("resolve final summary for long-term memory failed, requestId={}", turn.requestId(), e);
        }
        return turn;
    }

    private String safeBuildMedium(String sessionId, String currentRequestId) {
        try {
            return sessionContextMemoryService.buildHistoryDialogue(sessionId, currentRequestId);
        } catch (Exception e) {
            log.warn("build session(medium) memory failed, sessionId={}", sessionId, e);
            return "";
        }
    }

    private List<String> safeRecall(MemoryQuery query) {
        try {
            return longTermMemoryService.recall(query.ownerId(), query.sessionId(), query.query());
        } catch (Exception e) {
            log.warn("recall long-term memory failed, ownerId={}", query.ownerId(), e);
            return List.of();
        }
    }

    private boolean isMemoryEnabled() {
        return Boolean.TRUE.equals(reactorConfig.getMemoryEnabled());
    }

    private boolean isLongTermEnabled() {
        return isMemoryEnabled() && Boolean.TRUE.equals(reactorConfig.getLongTermMemoryEnabled());
    }
}
