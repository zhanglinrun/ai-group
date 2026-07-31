package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

/** Framework-neutral boundary for durable Deep Research graph execution. */
public interface GraphPort {

    GraphRunHandle start(GraphRunRequest request) throws Exception;

    GraphRunSnapshot resume(GraphRunResumeRequest request) throws Exception;
}
