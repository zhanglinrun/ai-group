package com.linrun.agent.domain.agent.reactor.service;

import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@Slf4j
@Service
public class EmbeddingService {
    private static final int MAX_INPUT_CHARS = 8000;
    private static final int MAX_INPUT_TOKENS = 2048;
    private static final TokenCounter TOKEN_COUNTER = new TokenCounter();
    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<List<Float>> getVectorBatch(List<String> text) {
        try {
            List<String> boundedInputs = text == null ? List.of() : text.stream()
                    .map(this::boundInput)
                    .toList();
            if (boundedInputs.isEmpty()) {
                return List.of();
            }
            return embeddingModel.embed(boundedInputs).stream()
                    .map(this::box)
                    .toList();
        } catch (Exception e) {
            log.error("embedding failed errorType={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    public List<Float> getVector(String text) {
        List<List<Float>> vectorBatch = getVectorBatch(Collections.singletonList(text));
        if (CollectionUtils.isNotEmpty(vectorBatch)) {
            return vectorBatch.get(0);
        }
        return null;
    }

    public boolean healthCheck() {
        List<Float> vector = getVector("health_check");
        return CollectionUtils.isNotEmpty(vector);
    }

    private String boundInput(String input) {
        String bounded = StringUtils.left(StringUtils.defaultString(input), MAX_INPUT_CHARS);
        return TOKEN_COUNTER.truncateTextToTokens(bounded, MAX_INPUT_TOKENS);
    }

    private List<Float> box(float[] vector) {
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
