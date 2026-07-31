package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import java.util.Objects;

/** Resume carries the original immutable graph request; adapters decide safe resume semantics. */
public record GraphRunResumeRequest(GraphRunRequest request) {

    public GraphRunResumeRequest {
        Objects.requireNonNull(request, "GraphRunRequest must not be null");
    }
}
