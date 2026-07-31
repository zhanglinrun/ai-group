package com.linrun.agent.domain.agent.runtime.deepresearch.graph.saa;

import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphNode;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphNodeFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimum P10 SAA node set. P40 replaces these deterministic probes with the full DEEP topology. */
@Component
public class SaaGraphNodeFactory implements GraphNodeFactory {

    public static final String INTAKE = "intake";
    public static final String PLANNER = "planner";
    public static final String EVIDENCE_MERGER = "evidence_merger";
    public static final String WRITER = "writer";

    @Override
    public Map<String, GraphNode> createNodes() {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        nodes.put(INTAKE, new IntakeNode());
        nodes.put(PLANNER, new PlannerNode());
        nodes.put(EVIDENCE_MERGER, new EvidenceMergerNode());
        nodes.put(WRITER, new WriterNode());
        return Map.copyOf(nodes);
    }
}
