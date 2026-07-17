package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.memory.ConversationMemoryManagerImpl;
import com.linrun.agent.domain.agent.memory.LongTermMemoryEntry;
import com.linrun.agent.domain.agent.memory.LongTermMemoryService;
import com.linrun.agent.domain.agent.memory.LongTermMemoryType;
import com.linrun.agent.domain.agent.memory.MemoryQuery;
import com.linrun.agent.domain.agent.memory.MemoryTurn;
import com.linrun.agent.domain.agent.memory.SessionContextMemoryService;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.context.ContextTrustBoundary;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 对话记忆管理器编排测试（mock 中期/长期，同步执行器，离线可跑）。
 */
public class ConversationMemoryManagerTest {

    private ReactorConfig config(boolean memoryEnabled, boolean longTermEnabled) {
        ReactorConfig cfg = new ReactorConfig();
        ReflectionTestUtils.setField(cfg, "memoryEnabled", memoryEnabled);
        ReflectionTestUtils.setField(cfg, "longTermMemoryEnabled", longTermEnabled);
        return cfg;
    }

    private ConversationMemoryManagerImpl manager(SessionContextMemoryService medium,
                                                  LongTermMemoryService longTerm,
                                                  ExecutionLedgerQueryService query,
                                                  ReactorConfig cfg) {
        ConversationMemoryManagerImpl mgr = new ConversationMemoryManagerImpl(medium, longTerm, cfg);
        // 同步执行器，便于断言异步落库效果
        ReflectionTestUtils.setField(mgr, "memoryExecutor", (Executor) Runnable::run);
        return mgr;
    }

    @Test
    public void shouldPrependLongTermRecallBeforeSessionMemory() {
        SessionContextMemoryService medium = Mockito.mock(SessionContextMemoryService.class);
        Mockito.when(medium.buildHistoryDialogue("s1", "r1")).thenReturn("## 单会话历史记忆\n\n### Run r0");
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(longTerm.recall("u1", "s1", "现在的问题")).thenReturn(List.of("你上次说喜欢 Java"));

        ConversationMemoryManagerImpl mgr = manager(medium, longTerm,
                Mockito.mock(ExecutionLedgerQueryService.class), config(true, true));

        String block = mgr.assembleHistoryBlock(new MemoryQuery("u1", "s1", "r1", "现在的问题"));

        Assert.assertTrue(block.contains("## 长期记忆（跨会话）"));
        Assert.assertTrue(block.contains("你上次说喜欢 Java"));
        Assert.assertTrue(block.contains("## 单会话历史记忆"));
        Assert.assertTrue(block.indexOf("长期记忆") < block.indexOf("单会话历史记忆"));
        Assert.assertTrue(block.startsWith(ContextTrustBoundary.START_PREFIX));
    }

    @Test
    public void shouldReturnOnlySessionMemoryWhenNoLongTermHit() {
        SessionContextMemoryService medium = Mockito.mock(SessionContextMemoryService.class);
        Mockito.when(medium.buildHistoryDialogue("s1", "r1")).thenReturn("## 单会话历史记忆\n\n### Run r0");
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(longTerm.recall(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of());

        ConversationMemoryManagerImpl mgr = manager(medium, longTerm,
                Mockito.mock(ExecutionLedgerQueryService.class), config(true, true));

        String block = mgr.assembleHistoryBlock(new MemoryQuery("u1", "s1", "r1", "q"));
        Assert.assertFalse(block.contains("长期记忆"));
        Assert.assertTrue(block.contains("## 单会话历史记忆"));
    }

    @Test
    public void shouldRenderStructuredMemoryMetadataInsideUntrustedBoundary() {
        SessionContextMemoryService medium = Mockito.mock(SessionContextMemoryService.class);
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(longTerm.recallEntries("u1", "s1", "q")).thenReturn(List.of(
                LongTermMemoryEntry.builder()
                        .id("memory-1")
                        .ownerId("u1")
                        .type(LongTermMemoryType.PREFERENCE)
                        .memoryKey("answer-style")
                        .content("用户偏好简洁回答")
                        .source("explicit-user-statement")
                        .confidence(0.9d)
                        .version(2L)
                        .build()
        ));
        ConversationMemoryManagerImpl mgr = manager(medium, longTerm,
                Mockito.mock(ExecutionLedgerQueryService.class), config(true, true));

        String block = mgr.assembleHistoryBlock(new MemoryQuery("u1", "s1", "r1", "q"));

        Assert.assertTrue(block.startsWith(ContextTrustBoundary.START_PREFIX));
        Assert.assertTrue(block.contains("[PREFERENCE source=explicit-user-statement confidence=0.90 version=2]"));
        Assert.assertTrue(block.contains("用户偏好简洁回答"));
        Mockito.verify(longTerm, Mockito.never()).recall(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void shouldFailOpenWhenLongTermThrows() {
        SessionContextMemoryService medium = Mockito.mock(SessionContextMemoryService.class);
        Mockito.when(medium.buildHistoryDialogue("s1", "r1")).thenReturn("## 单会话历史记忆");
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(longTerm.recall(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("qdrant down"));

        ConversationMemoryManagerImpl mgr = manager(medium, longTerm,
                Mockito.mock(ExecutionLedgerQueryService.class), config(true, true));

        // 长期记忆异常时退化为中期记忆，不抛出
        String block = mgr.assembleHistoryBlock(new MemoryQuery("u1", "s1", "r1", "q"));
        Assert.assertTrue(block.startsWith(ContextTrustBoundary.START_PREFIX));
        Assert.assertTrue(block.contains("## 单会话历史记忆"));
    }

    @Test
    public void shouldPersistTurnWhenLongTermEnabled() {
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        ConversationMemoryManagerImpl mgr = manager(Mockito.mock(SessionContextMemoryService.class), longTerm,
                Mockito.mock(ExecutionLedgerQueryService.class), config(true, true));

        MemoryTurn turn = new MemoryTurn("u1", "s1", "r1", "问题", "答复");
        mgr.persistTurnAsync(turn);

        Mockito.verify(longTerm).save(turn);
    }

    @Test
    public void shouldSkipPersistWhenLongTermDisabled() {
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        ConversationMemoryManagerImpl mgr = manager(Mockito.mock(SessionContextMemoryService.class), longTerm,
                Mockito.mock(ExecutionLedgerQueryService.class), config(true, false));

        mgr.persistTurnAsync(new MemoryTurn("u1", "s1", "r1", "问题", "答复"));
        Mockito.verifyNoInteractions(longTerm);
    }
}
