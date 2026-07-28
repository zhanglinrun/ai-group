package com.linrun.agent.domain.agent.memory;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.context.ContextTrustBoundary;
import com.linrun.agent.types.agent.config.AgentExecutorNames;

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
    private final ReactorConfig reactorConfig;

    @Resource(name = AgentExecutorNames.TASK_EXECUTOR)
    private Executor memoryExecutor;

    public ConversationMemoryManagerImpl(SessionContextMemoryService sessionContextMemoryService,
                                         LongTermMemoryService longTermMemoryService,
                                         ReactorConfig reactorConfig) {
        this.sessionContextMemoryService = sessionContextMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.reactorConfig = reactorConfig;
    }

    @Override
    public String assembleHistoryBlock(MemoryQuery query) {
        if (query == null) {
            return "";
        }
        String medium = safeBuildMedium(query.sessionId(), query.currentRequestId());
        if (!isMemoryEnabled()) {
            return wrapMemoryBlock(medium);
        }
        List<String> longTerm = safeRecallStructuredFirst(query);
        if (longTerm.isEmpty()) {
            return wrapMemoryBlock(medium);
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
        return wrapMemoryBlock(builder.toString());
    }

    @Override
    public void persistTurnAsync(MemoryTurn turn) {
        if (!isLongTermEnabled() || turn == null || StringUtils.isBlank(turn.ownerId())) {
            return;
        }
        Runnable task = () -> {
            try {
                // Every completed turn may become owner-scoped semantic history; only explicit
                // user statements may additionally be promoted to a durable profile entry.
                longTermMemoryService.save(turn);
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

    private List<String> safeRecallStructuredFirst(MemoryQuery query) {
        try {
            List<LongTermMemoryEntry> entries = longTermMemoryService.recallEntries(
                    query.ownerId(), query.sessionId(), query.query());
            if (entries != null && !entries.isEmpty()) {
                return entries.stream().map(LongTermMemoryEntry::toPromptSnippet).toList();
            }
        } catch (Exception e) {
            log.warn("recall structured long-term memory failed, ownerId={}", query.ownerId(), e);
        }
        return safeRecall(query);
    }

    private String wrapMemoryBlock(String block) {
        if (StringUtils.isBlank(block)) {
            return "";
        }
        return ContextTrustBoundary.wrap("retrieved-conversation-memory", block.trim());
    }

    private boolean isMemoryEnabled() {
        return Boolean.TRUE.equals(reactorConfig.getMemoryEnabled());
    }

    private boolean isLongTermEnabled() {
        return isMemoryEnabled() && Boolean.TRUE.equals(reactorConfig.getLongTermMemoryEnabled());
    }
}
