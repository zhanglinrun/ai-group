package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceMapper;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentTraceMapperTest {

    @Test
    public void mapsRequiredGenAiAndLedgerCorrelationAttributesWithoutPayloadBodies() {
        AgentRunState state = AgentRunState.builder().runId(73L).build();
        state.nextContextRevision();
        state.recordEvidenceCount(4);
        AgentContext context = AgentContext.builder()
                .requestId("request-73")
                .query("the prompt body must never reach trace attributes")
                .executionProfile(AgentExecutionProfile.DEEP)
                .agentRunState(state)
                .build();

        AgentTraceMapper mapper = new AgentTraceMapper();
        Map<String, String> attributes = mapper.model(context, "qwen-plus", 91L);

        assertEquals("spring_ai_alibaba", attributes.get(AgentTraceMapper.GEN_AI_SYSTEM));
        assertEquals("qwen-plus", attributes.get(AgentTraceMapper.GEN_AI_REQUEST_MODEL));
        assertEquals("chat", attributes.get(AgentTraceMapper.GEN_AI_OPERATION_NAME));
        assertEquals("73", attributes.get(AgentTraceMapper.RUN_ID));
        assertEquals("73", attributes.get(AgentTraceMapper.LEDGER_RUN_ID));
        assertEquals("91", attributes.get(AgentTraceMapper.LEDGER_LLM_INVOCATION_ID));
        assertEquals("4", attributes.get(AgentTraceMapper.EVIDENCE_COUNT));
        assertFalse(attributes.values().stream().anyMatch(value -> value.contains("prompt body")));
        assertFalse(attributes.keySet().stream().anyMatch(key -> key.contains("prompt") || key.contains("input")));
    }

    @Test
    public void allowlistDropsSensitiveAndArbitraryPayloadFields() {
        AgentTraceMapper mapper = new AgentTraceMapper();
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put(AgentTraceMapper.TOOL_NAME, "search_web");
        candidates.put(AgentTraceMapper.LEDGER_TOOL_INVOCATION_ID, 19L);
        candidates.put("gen_ai.input.messages", "full prompt");
        candidates.put("aigroup.tool.arguments", "{\"authorization\":\"Bearer raw-secret\"}");
        candidates.put(AgentTraceMapper.GEN_AI_REQUEST_MODEL, "apiKey=raw-secret");

        Map<String, String> attributes = mapper.sanitize(candidates);

        assertEquals("search_web", attributes.get(AgentTraceMapper.TOOL_NAME));
        assertEquals("19", attributes.get(AgentTraceMapper.LEDGER_TOOL_INVOCATION_ID));
        assertNull(attributes.get("gen_ai.input.messages"));
        assertNull(attributes.get("aigroup.tool.arguments"));
        assertNull(attributes.get(AgentTraceMapper.GEN_AI_REQUEST_MODEL));
        assertTrue(attributes.size() == 2);
    }
}
