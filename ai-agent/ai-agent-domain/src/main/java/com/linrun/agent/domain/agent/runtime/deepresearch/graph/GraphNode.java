package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import java.util.Map;

/** A small business-facing node contract. SAA conversion happens only in the adapter package. */
@FunctionalInterface
public interface GraphNode {

    Map<String, Object> execute(Map<String, Object> state);
}
