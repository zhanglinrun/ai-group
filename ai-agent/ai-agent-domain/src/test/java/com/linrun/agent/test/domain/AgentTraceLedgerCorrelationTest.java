package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceMapper;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceRecorder;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceScope;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class AgentTraceLedgerCorrelationTest {

    @Test
    public void oneTraceIdCorrelatesRunModelAndToolLedgerIdentifiers() {
        AgentTraceRecorder recorder = AgentTraceRecorder.noop();
        AgentRunState state = AgentRunState.builder().runId(310L).build();
        AgentContext context = AgentContext.builder()
                .requestId("request-310")
                .agentRunState(state)
                .agentTraceRecorder(recorder)
                .build();
        AgentTraceMapper mapper = new AgentTraceMapper();

        AgentTraceScope session = recorder.start("session", null, mapper.session(context));
        AgentTraceScope run = recorder.start("run", session, mapper.run(context));
        state.activateTrace(session, run);
        AgentTraceScope model = recorder.start("model", run, mapper.model(context, "qwen-plus", 311L));
        state.bindLlmTraceScope(311L, model);
        AgentTraceScope tool = recorder.start("tool", model,
                mapper.tool(context, "search_web", "local", 312L, "call-312"));
        state.bindToolTraceScope("call-312", tool);

        assertNotNull(state.getTraceId());
        assertEquals(session.traceId(), state.getTraceId());
        assertEquals(run.traceId(), model.traceId());
        assertEquals(model.traceId(), tool.traceId());
        assertSame(model, state.resolveLlmTraceScope(311L));
        assertSame(tool, state.resolveToolTraceScope("call-312"));
        Map<String, String> toolAttributes = mapper.tool(context, "search_web", "local", 312L, "call-312");
        assertEquals("310", toolAttributes.get(AgentTraceMapper.LEDGER_RUN_ID));
        assertEquals("312", toolAttributes.get(AgentTraceMapper.LEDGER_TOOL_INVOCATION_ID));
        assertEquals("call-312", toolAttributes.get(AgentTraceMapper.LEDGER_TOOL_CALL_ID));
        assertFalse(toolAttributes.keySet().stream().anyMatch(key -> key.contains("argument") || key.contains("file")));

        recorder.end(tool, null);
        recorder.end(model, null);
        recorder.end(run, null);
        recorder.end(session, null);
    }
}
