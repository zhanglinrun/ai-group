package com.aigroup.researchpilot.spike;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCompatibilitySpikeTest {

    @Test
    void executesConditionalFanOutFanInAndWritesCheckpoint() throws Exception {
        MemorySaver saver = MemorySaver.builder().build();
        StateGraph graph = new StateGraph()
                .addNode("route", AsyncNodeAction.node_async(state -> Map.of("route", "fanout")))
                .addNode("fanout", AsyncNodeAction.node_async(state -> Map.of("fanout", true)))
                .addNode("research-a", AsyncNodeAction.node_async(state -> Map.of("branch-a", "complete")))
                .addNode("research-b", AsyncNodeAction.node_async(state -> Map.of("branch-b", "complete")))
                .addNode("merge", AsyncNodeAction.node_async(state -> Map.of("merged", true)));

        graph.addEdge(StateGraph.START, "route");
        graph.addConditionalEdges(
                "route",
                AsyncEdgeAction.edge_async(state -> state.value("route").orElse("error").toString()),
                Map.of("fanout", "fanout"));
        graph.addEdge("fanout", List.of("research-a", "research-b"));
        graph.addEdge(List.of("research-a", "research-b"), "merge");
        graph.addEdge("merge", StateGraph.END);

        CompiledGraph compiled = graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build());
        RunnableConfig config = RunnableConfig.builder().threadId("p00-spike").build();

        var output = compiled.invoke(Map.of("question", "ResearchPilot"), config).orElseThrow();

        assertThat(output.value("merged")).contains(true);
        assertThat(compiled.getState(config)).isNotNull();
        assertThat(saver.get(config)).isPresent();
    }
}
