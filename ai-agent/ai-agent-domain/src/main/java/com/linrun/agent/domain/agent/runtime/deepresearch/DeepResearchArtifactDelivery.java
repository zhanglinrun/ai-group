package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactBinding;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;

import java.util.List;

/** Produces the requested DEEP delivery artifact after the canonical Markdown report is persisted. */
public interface DeepResearchArtifactDelivery {

    List<ToolArtifactBinding> deliver(AgentContext context,
                                      AgentRequest request,
                                      String checkpointThreadId,
                                      String canonicalMarkdown) throws Exception;

    /**
     * P100 delivery boundary.  Existing implementations keep the legacy
     * method as a compatibility fallback while deterministic renderers receive
     * the complete, already-gated ReportSpec.
     */
    default List<ToolArtifactBinding> deliver(AgentContext context,
                                              AgentRequest request,
                                              String checkpointThreadId,
                                              ReportSpec reportSpec,
                                              String canonicalMarkdown) throws Exception {
        return deliver(context, request, checkpointThreadId, canonicalMarkdown);
    }
}
