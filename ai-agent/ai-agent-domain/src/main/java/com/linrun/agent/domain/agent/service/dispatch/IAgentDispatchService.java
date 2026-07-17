package com.linrun.agent.domain.agent.service.dispatch;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.printer.Printer;

/**
 * Agent 应用层调度接口。
 */
public interface IAgentDispatchService {

    void dispatch(AgentRequest request, Printer printer) throws Exception;
}
