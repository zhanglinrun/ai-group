package com.linrun.agent.domain.agent.service.armory.node;

import com.linrun.agent.domain.agent.model.entity.ArmoryCommandEntity;
import com.linrun.agent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.linrun.agent.domain.agent.model.valobj.enums.AiClientAdvisorTypeEnumVO;
import com.linrun.agent.domain.agent.model.valobj.AiClientAdvisorVO;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import com.linrun.agent.types.design.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;

import java.util.List;

/**
 * 顾问角色节点
 */
@Slf4j
@Service
public class AiClientAdvisorNode extends AbstractArmorySupport {

    private final HybridRetriever hybridRetriever;

    public AiClientAdvisorNode(ObjectProvider<HybridRetriever> hybridRetriever) {
        this.hybridRetriever = hybridRetriever.getIfAvailable();
    }

    @Resource
    private AiClientNode aiClientNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("armory node start node=advisor commandType={} commandIdCount={}",
                requestParameter == null ? null : requestParameter.getCommandType(),
                requestParameter == null || requestParameter.getCommandIdList() == null
                        ? 0 : requestParameter.getCommandIdList().size());

        List<AiClientAdvisorVO> aiClientAdvisorList = dynamicContext.getValue(dataName());

        if (aiClientAdvisorList == null || aiClientAdvisorList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client advisor");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientAdvisorVO aiClientAdvisorVO : aiClientAdvisorList) {
            // 构建顾问访问对象
            Advisor advisor = createAdvisor(aiClientAdvisorVO);
            // 使用业务 ID 注册顾问运行时对象。
            aiClientRuntimeRegistry.registerAdvisor(aiClientAdvisorVO.getAdvisorId(), advisor);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientNode;
    }

    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName();
    }

    private Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO) {
        String advisorType = aiClientAdvisorVO.getAdvisorType();
        AiClientAdvisorTypeEnumVO advisorTypeEnum = AiClientAdvisorTypeEnumVO.getByCode(advisorType);
        return advisorTypeEnum.createAdvisor(aiClientAdvisorVO, hybridRetriever);
    }

}
