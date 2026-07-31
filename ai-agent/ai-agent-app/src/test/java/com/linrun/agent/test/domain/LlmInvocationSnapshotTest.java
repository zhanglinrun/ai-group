package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.llm.InvocationSnapshotFactory;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmInvocationSnapshotTest {

    @Test
    void createsStableSecretFreeSnapshotForAgentModelCall() {
        LLMSettings settings = LLMSettings.builder()
                .model("qwen-plus")
                .apiKey("must-not-be-persisted")
                .maxTokens(1200)
                .temperature(0.2D)
                .inputCreditsPerMillion(5L)
                .outputCreditsPerMillion(30L)
                .extParams(Map.of("response_format", "json"))
                .build();

        InvocationSnapshotFactory.InvocationSnapshot first = InvocationSnapshotFactory.forAgentCall(
                Map.of("prompt", "hello"), settings, new ToolCollection());
        InvocationSnapshotFactory.InvocationSnapshot second = InvocationSnapshotFactory.forAgentCall(
                Map.of("prompt", "hello"), settings, new ToolCollection());

        assertEquals(first.promptHash(), second.promptHash());
        assertEquals(first.configHash(), second.configHash());
        assertEquals(64, first.promptHash().length());
        assertEquals(64, first.configHash().length());
        assertFalse(first.modelParametersJson().contains("must-not-be-persisted"));
        assertTrue(first.toolSnapshotJson().contains("[]"));
        assertEquals("[]", first.skillSnapshotJson());
    }
}
