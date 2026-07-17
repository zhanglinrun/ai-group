package com.linrun.agent.domain.agent.service.execute;

import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.printer.Printer;

/**
 * 领域执行策略接口。
 * trigger 负责把协议流适配成 Printer，domain 只消费稳定抽象。
 */
public interface IExecuteStrategy {

    void execute(AgentRequest request, Printer printer) throws Exception;
}
