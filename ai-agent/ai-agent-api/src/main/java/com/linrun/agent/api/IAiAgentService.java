package com.linrun.agent.api;

import com.linrun.agent.api.dto.AiAgentResponseDTO;
import com.linrun.agent.api.dto.ArmoryAgentRequestDTO;
import com.linrun.agent.api.dto.ArmoryApiRequestDTO;
import com.linrun.agent.api.response.Response;

import java.util.List;


public interface IAiAgentService {

    /**
     * 装配智能体
     */
    Response<Boolean> armoryAgent(ArmoryAgentRequestDTO request);

    /**
     * 查询可用的智能体列表
     */
    Response<List<AiAgentResponseDTO>> queryAvailableAgents();

    /**
     * 装配API
     */
    Response<Boolean> armoryApi(ArmoryApiRequestDTO request);

}
