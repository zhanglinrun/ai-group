package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import java.util.Map;

/** Supplies named business nodes without exposing any graph framework types. */
public interface GraphNodeFactory {

    Map<String, GraphNode> createNodes();
}
