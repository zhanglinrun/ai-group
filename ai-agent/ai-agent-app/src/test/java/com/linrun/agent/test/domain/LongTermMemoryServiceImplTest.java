package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.memory.LongTermMemoryEntry;
import com.linrun.agent.domain.agent.memory.LongTermMemoryPreference;
import com.linrun.agent.domain.agent.memory.LongTermMemoryServiceImpl;
import com.linrun.agent.domain.agent.memory.LongTermMemoryType;
import com.linrun.agent.domain.agent.memory.MemoryTurn;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalHit;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalRequest;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

public class LongTermMemoryServiceImplTest {

    @Test
    public void shouldSaveOnlyExplicitProfileAndNeverPersistRawQaPair() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        LongTermMemoryServiceImpl service = service(repository, Mockito.mock(HybridRetriever.class), true);

        service.save(new MemoryTurn("user-1", "session-1", "req-1", "请记住：我叫小王", "已记录"));

        Mockito.verify(repository, Mockito.never()).saveMemory(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyMap(), Mockito.anyString(), Mockito.any());
        Mockito.verify(repository).saveUserProfile(
                Mockito.eq("user-1"), Mockito.eq("fact:user-name"), Mockito.eq("FACT"),
                Mockito.eq("用户明确声明: 我叫小王"), Mockito.eq(0.8d),
                Mockito.eq("explicit-user-memory"), Mockito.any());
    }

    @Test
    public void shouldNotPersistOrdinaryConversationAsLongTermMemory() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        LongTermMemoryServiceImpl service = service(repository, Mockito.mock(HybridRetriever.class), true);

        service.save(new MemoryTurn("user-1", "session-1", "req-1", "解释 Java 虚拟线程", "回答"));

        Mockito.verify(repository, Mockito.never()).saveMemory(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyMap(), Mockito.anyString(), Mockito.any());
        Mockito.verify(repository, Mockito.never()).saveUserProfile(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyDouble(), Mockito.anyString(), Mockito.any());
    }

    @Test
    public void shouldNoOpWhenLongTermMemoryDisabled() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        HybridRetriever retriever = Mockito.mock(HybridRetriever.class);
        LongTermMemoryServiceImpl service = service(repository, retriever, false);

        service.save(new MemoryTurn("user-1", "session-1", "req-1", "q", "a"));

        Assert.assertTrue(service.recall("user-1", "session-1", "q").isEmpty());
        Mockito.verifyNoInteractions(repository, retriever);
    }

    @Test
    public void shouldScopeRecallByOwnerAndExcludeCurrentConversation() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        HybridRetriever retriever = Mockito.mock(HybridRetriever.class);
        Mockito.when(repository.getUserProfile("user-1")).thenReturn(List.of());
        Mockito.when(retriever.retrieve(Mockito.any())).thenReturn(List.of(
                hit("current", "current-key", "current", "session-1", 1, 0.9d),
                hit("other", "other-key", "other", "session-2", 1, 0.8d)));
        LongTermMemoryServiceImpl service = service(repository, retriever, true);

        List<String> recalled = service.recall("user-1", "session-1", "query");

        Assert.assertEquals(List.of("other"), recalled);
        ArgumentCaptor<HybridRetrievalRequest> captor = ArgumentCaptor.forClass(HybridRetrievalRequest.class);
        Mockito.verify(retriever).retrieve(captor.capture());
        Assert.assertEquals("user-1", captor.getValue().getOwnerId());
        Assert.assertEquals(List.of("session_summary", "cross_summary"),
                captor.getValue().getDocTypes());
    }

    @Test
    public void shouldKeepCurrentConversationSessionSummaryWhileExcludingItsRawQa() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        HybridRetriever retriever = Mockito.mock(HybridRetriever.class);
        Mockito.when(repository.getUserProfile("user-1")).thenReturn(List.of());
        Mockito.when(retriever.retrieve(Mockito.any())).thenReturn(List.of(
                hit("qa", "qa-key", "raw qa", "session-1", 1, 0.9d),
                HybridRetrievalHit.builder()
                        .memoryId("summary")
                        .content("session summary")
                        .docType("session_summary")
                        .conversationId("session-1")
                        .metadata(Map.of("memoryKey", "summary-key"))
                        .fusedScore(0.8d)
                        .source("BOTH")
                        .build()));
        LongTermMemoryServiceImpl service = service(repository, retriever, true);

        Assert.assertEquals(List.of("session summary"), service.recall("user-1", "session-1", "query"));
    }

    @Test
    public void shouldPreferNewestVersionForSameMemoryKey() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        HybridRetriever retriever = Mockito.mock(HybridRetriever.class);
        Mockito.when(repository.getUserProfile("user-1")).thenReturn(List.of());
        Mockito.when(retriever.retrieve(Mockito.any())).thenReturn(List.of(
                hit("old", "answer-style", "old", "session-1", 1, 0.9d),
                hit("new", "answer-style", "new", "session-2", 2, 0.7d)));
        LongTermMemoryServiceImpl service = service(repository, retriever, true);

        List<LongTermMemoryEntry> entries = service.recallEntries("user-1", "current", "style");

        Assert.assertEquals(1, entries.size());
        Assert.assertEquals("new", entries.get(0).getContent());
        Assert.assertEquals(2L, entries.get(0).getVersion());
    }

    @Test
    public void shouldDeleteWithinOwnerBoundary() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        Mockito.when(repository.deleteMemory("user-1", "memory-1")).thenReturn(true);
        LongTermMemoryServiceImpl service = service(repository, Mockito.mock(HybridRetriever.class), true);

        Assert.assertTrue(service.delete("user-1", "memory-1"));
        Mockito.verify(repository).deleteMemory("user-1", "memory-1");
    }

    private LongTermMemoryServiceImpl service(PgVectorMemoryRepository repository,
                                              HybridRetriever retriever,
                                              boolean enabled) {
        return new LongTermMemoryServiceImpl(provider(repository), provider(retriever), config(enabled));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private ReactorConfig config(boolean enabled) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "memoryEnabled", enabled);
        ReflectionTestUtils.setField(config, "longTermMemoryEnabled", enabled);
        ReflectionTestUtils.setField(config, "longTermMemoryTopK", 5);
        ReflectionTestUtils.setField(config, "longTermMemoryScoreThreshold", 0.6f);
        ReflectionTestUtils.setField(config, "longTermMemoryDecayHalfLifeDays", 30);
        return config;
    }

    private HybridRetrievalHit hit(String id, String key, String content,
                                   String conversationId, long version, double score) {
        return HybridRetrievalHit.builder()
                .memoryId(id)
                .content(content)
                .docType("qa_pair")
                .conversationId(conversationId)
                .metadata(Map.of(
                        "memoryKey", key,
                        "memoryType", LongTermMemoryType.PREFERENCE.name(),
                        "version", version,
                        "createdAt", System.currentTimeMillis()))
                .fusedScore(score)
                .source("BOTH")
                .build();
    }
}
