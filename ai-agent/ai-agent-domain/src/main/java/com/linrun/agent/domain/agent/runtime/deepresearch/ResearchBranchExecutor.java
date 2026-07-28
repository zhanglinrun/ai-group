package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;

public interface ResearchBranchExecutor {

    ResearchBranchResult execute(AgentContext parentContext,
                                 AgentRequest parentRequest,
                                 ResearchPlan plan,
                                 int researcherIndex) throws Exception;
}
