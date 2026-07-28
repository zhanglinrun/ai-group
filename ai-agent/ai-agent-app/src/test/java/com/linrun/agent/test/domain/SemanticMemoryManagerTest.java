package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.rag.memory.SemanticMemoryManager;
import com.linrun.agent.domain.agent.rag.memory.SemanticMemoryType;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.domain.agent.reactor.service.EmbeddingService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SemanticMemoryManagerTest {

    @Test
    public void shouldIsolateSessionSummaryByOwnerAndConversation() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        Mockito.when(repository.findByOwnerDocTypeAndConversation(
                        "owner-1", "session_summary", "conversation-b", 1))
                .thenReturn(List.of(Map.of("content", "old-b")));
        Mockito.when(chatModel.call(Mockito.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("summary-b")))));
        Mockito.when(repository.saveMemory(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())).thenReturn(true);
        SemanticMemoryManager manager = new SemanticMemoryManager(
                repository, Mockito.mock(HybridRetriever.class), Mockito.mock(JdbcTemplate.class),
                chatModel, "summary-model");

        Assert.assertTrue(manager.mergeSessionSummary("owner-1", "conversation-b", "new-b"));

        Mockito.verify(repository).findByOwnerDocTypeAndConversation(
                "owner-1", "session_summary", "conversation-b", 1);
        Mockito.verify(repository, Mockito.never()).findByOwnerAndDocType(
                "owner-1", "session_summary", 1);
        Mockito.verify(repository).saveMemory(Mockito.anyString(), Mockito.eq("owner-1"),
                Mockito.eq(SemanticMemoryType.SESSION_SUMMARY.dbValue()), Mockito.eq("summary-b"),
                Mockito.anyMap(), Mockito.eq("conversation-b"));
    }

    @Test
    public void shouldPersistCrossSummaryAndWatermarkInOneStatement() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        Mockito.when(embeddingService.getVector("summary"))
                .thenReturn(Collections.nCopies(1024, 0.1f));
        Mockito.when(jdbcTemplate.update(Mockito.anyString(), Mockito.<Object[]>any())).thenReturn(1);
        PgVectorMemoryRepository repository = new PgVectorMemoryRepository(jdbcTemplate, embeddingService);
        Timestamp watermark = Timestamp.from(Instant.parse("2026-07-27T00:00:00Z"));

        Assert.assertTrue(repository.saveMemoryWithWatermark(
                "memory-id", "owner-1", "cross_summary", "summary", Map.of(), null, watermark));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        Mockito.verify(jdbcTemplate).update(sql.capture(), args.capture());
        Assert.assertTrue(sql.getValue().contains("latest_qa_created_at"));
        Assert.assertEquals(watermark, args.getValue()[8]);
        Mockito.verifyNoMoreInteractions(jdbcTemplate);
    }
}
