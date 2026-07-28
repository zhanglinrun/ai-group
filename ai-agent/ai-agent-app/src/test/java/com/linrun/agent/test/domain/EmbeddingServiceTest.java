package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.reactor.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {

    @Test
    void usesSpringAiEmbeddingModelAndBoundsInput() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[]{0.25f, -0.5f}));
        EmbeddingService service = new EmbeddingService(embeddingModel);

        assertEquals(List.of(List.of(0.25f, -0.5f)), service.getVectorBatch(List.of("word ".repeat(3000))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> inputs = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(inputs.capture());
        assertTrue(inputs.getValue().getFirst().length() <= 8000);
    }
}
