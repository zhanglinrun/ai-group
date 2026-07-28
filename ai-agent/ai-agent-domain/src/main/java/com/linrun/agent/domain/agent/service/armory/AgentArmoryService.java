package com.linrun.agent.domain.agent.service.armory;

import com.linrun.agent.types.design.tree.StrategyHandler;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.entity.ArmoryCommandEntity;
import com.linrun.agent.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import com.linrun.agent.domain.agent.model.valobj.AiAgentVO;
import com.linrun.agent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 领域装配服务。
 * 负责把装配命令编排为领域策略调用。
 */
@Service
public class AgentArmoryService implements IArmoryService {

    @Resource
    private IAgentRepository repository;

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    @Override
    public List<AiAgentVO> acceptArmoryAllAvailableAgents() {
        List<AiAgentVO> aiAgentVOS = repository.queryAvailableAgents();
        for (AiAgentVO aiAgentVO : aiAgentVOS) {
            acceptArmoryAgent(aiAgentVO.getAgentId());
        }
        return aiAgentVOS;
    }

    @Override
    public void acceptArmoryAgent(String agentId) {
        List<AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOS = repository.queryAiAgentClientsByAgentId(agentId);
        if (aiAgentClientFlowConfigVOS.isEmpty()) {
            return;
        }

        List<String> commandIdList = aiAgentClientFlowConfigVOS.stream()
                .map(AiAgentClientFlowConfigVO::getClientId)
                .collect(Collectors.toList());

        try {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                    defaultArmoryStrategyFactory.armoryStrategyHandler();

            armoryStrategyHandler.apply(
                    ArmoryCommandEntity.builder()
                            .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                            .commandIdList(commandIdList)
                            .build(),
                    new DefaultArmoryStrategyFactory.DynamicContext());
        } catch (Exception e) {
            throw new RuntimeException("装配智能体失败", e);
        }
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        return repository.queryAvailableAgents();
    }

    @Override
    public void acceptArmoryAgentClientModelApi(String apiId) {
        try {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                    defaultArmoryStrategyFactory.armoryStrategyHandler();

            armoryStrategyHandler.apply(
                    ArmoryCommandEntity.builder()
                            .commandType(AiAgentEnumVO.AI_CLIENT_API.getCode())
                            .commandIdList(Collections.singletonList(apiId))
                            .build(),
                    new DefaultArmoryStrategyFactory.DynamicContext());
        } catch (Exception e) {
            throw new RuntimeException("装配智能体失败", e);
        }
    }
}
