package com.linrun.agent.domain.agent.runtime.deepresearch.graph.saa;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchBranchResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.DeepResearchCheckpointState;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunHandle;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunRequest;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.InMemoryGraphCheckpointPort;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class SaaGraphReportSpecTest {

    @Test
    public void shouldPersistGatedReportSpecBeforeTerminalDelivery() throws Exception {
        InMemoryGraphCheckpointPort checkpoints = new InMemoryGraphCheckpointPort();
        SaaGraphPortAdapter adapter = new SaaGraphPortAdapter((context, request, plan, index) -> {
            String section = plan.assignedSections(index).getFirst();
            ResearchEvidencePacket evidence = new ResearchEvidencePacket("claim-" + index, "source-" + index,
                    "https://source.example/" + index, "verified quote", "evidence-" + index, "hash-" + index,
                    1_000L + index, 0L, "FETCHED_PAGE", "HIGH", "FRESH", "trace-" + index,
                    "verified claim " + index, "SUPPORTS", 0, "verified quote".length(), false);
            return new ResearchBranchResult("researcher_" + index, List.of(section), "ignored raw prose",
                    List.of(evidence), List.of(), List.of(), 1L, 2L);
        }, checkpoints);
        AgentRequest request = AgentRequest.builder().requestId("p100-report-spec").sessionId("session-p100")
                .ownerId("1001").query("market and regulation").executionMode("DEEP").outputStyle("chat").build();
        GraphRunRequest graphRequest = GraphRunRequest.from(AgentContext.builder().requestId(request.getRequestId())
                .sessionId(request.getSessionId()).ownerId(1001L).query(request.getQuery()).build(), request);

        GraphRunHandle handle = adapter.start(graphRequest);
        Map<String, Object> state = checkpoints.find(SaaGraphPortAdapter.GRAPH_ID, graphRequest.threadId())
                .orElseThrow().checkpointState();

        Assert.assertTrue(handle.result().completed());
        Assert.assertTrue(state.get(DeepResearchCheckpointState.REPORT_SPEC) instanceof Map<?, ?>);
        Assert.assertTrue(state.get(DeepResearchCheckpointState.CITATION_GATE) instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) state.get(DeepResearchCheckpointState.CITATION_GATE);
        Assert.assertEquals(Boolean.TRUE, gate.get("passed"));
    }
}
