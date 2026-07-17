package com.linrun.agent.domain.agent.service.dispatch;

import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.domain.agent.service.execute.IExecuteStrategy;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.printer.Printer;

/**
 * Agent 领域调度器。
 * 所有请求都进入统一 Agent Loop，输出协议已由 trigger 适配为 Printer。
 */
@Service
public class AgentDispatchService implements IAgentDispatchService {

    private final IExecuteStrategy agentLoopExecuteStrategy;

    public AgentDispatchService(IExecuteStrategy agentLoopExecuteStrategy) {
        this.agentLoopExecuteStrategy = agentLoopExecuteStrategy;
    }

    @Override
    public void dispatch(AgentRequest request, Printer printer) throws Exception {
        if (request != null) {
            request.setAgentType(AgentType.AGENT_LOOP.getValue());
        }
        agentLoopExecuteStrategy.execute(request, printer);
    }
}
