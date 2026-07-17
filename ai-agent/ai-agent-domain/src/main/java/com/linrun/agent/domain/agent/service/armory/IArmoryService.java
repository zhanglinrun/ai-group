package com.linrun.agent.domain.agent.service.armory;

import com.linrun.agent.domain.agent.model.valobj.AiAgentVO;

import java.util.List;

/**
 * Agent 领域装配接口。
 */
public interface IArmoryService {

    List<AiAgentVO> acceptArmoryAllAvailableAgents();

    void acceptArmoryAgent(String agentId);

    List<AiAgentVO> queryAvailableAgents();

    void acceptArmoryAgentClientModelApi(String apiId);
}
