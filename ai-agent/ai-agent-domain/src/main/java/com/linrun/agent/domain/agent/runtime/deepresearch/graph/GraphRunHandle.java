package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchResult;

import java.util.Objects;

/** Completed synchronous handle retained until P30 introduces durable asynchronous run ownership. */
public record GraphRunHandle(String graphId,
                             String threadId,
                             DeepResearchResult result,
                             boolean resumed) {

    public GraphRunHandle {
        Objects.requireNonNull(graphId, "graphId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(result, "DeepResearchResult must not be null");
    }
}
