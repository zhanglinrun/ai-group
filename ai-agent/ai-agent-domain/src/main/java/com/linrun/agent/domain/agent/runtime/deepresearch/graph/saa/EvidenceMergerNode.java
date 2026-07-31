package com.linrun.agent.domain.agent.runtime.deepresearch.graph.saa;

import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphNode;

import java.util.Map;

/** P10 deterministic evidence-merge boundary; P40 provides the Evidence Ledger implementation. */
public final class EvidenceMergerNode implements GraphNode {

    @Override
    public Map<String, Object> execute(Map<String, Object> state) {
        return Map.of("evidence", "merged");
    }
}
