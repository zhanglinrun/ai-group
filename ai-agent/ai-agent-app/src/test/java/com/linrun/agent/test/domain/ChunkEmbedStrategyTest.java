package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.rag.ingest.ChunkEmbedStrategy;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class ChunkEmbedStrategyTest {

    @Test
    void constructsWithSpringAiDefaultPunctuationMarks() {
        assertDoesNotThrow(() -> new ChunkEmbedStrategy(mock(PgVectorMemoryRepository.class)));
    }
}
