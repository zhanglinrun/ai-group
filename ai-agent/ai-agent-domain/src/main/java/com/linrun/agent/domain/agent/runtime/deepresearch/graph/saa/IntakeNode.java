package com.linrun.agent.domain.agent.runtime.deepresearch.graph.saa;

import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphNode;

import java.util.Map;

/** P10 deterministic Intake boundary; P40 connects it to the Agent Harness. */
public final class IntakeNode implements GraphNode {

    @Override
    public Map<String, Object> execute(Map<String, Object> state) {
        return Map.of("intake", "accepted");
    }
}
