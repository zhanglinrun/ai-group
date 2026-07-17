package com.linrun.agent.domain.agent.service.armory.node;

import com.linrun.agent.domain.agent.model.entity.ArmoryCommandEntity;
import com.linrun.agent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MCP客户端配置节点
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport {

    @Resource
    private AiClientModelNode aiClientModelNode;

    @Resource
    private McpRegistry mcpRegistry;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("armory node start node=tool_mcp commandType={} commandIdCount={}",
                requestParameter == null ? null : requestParameter.getCommandType(),
                requestParameter == null || requestParameter.getCommandIdList() == null
                        ? 0 : requestParameter.getCommandIdList().size());

        // Reactor 使用全局启用的 MCP，所以每次装配时都刷新全局预热快照。
        mcpRegistry.preloadAllEnabledMcps();

        // fix 策略仍按 client 绑定 MCP 子集，因此这里同步刷新客户端与 MCP 的绑定关系。
        if (AiAgentEnumVO.AI_CLIENT.getCode().equals(requestParameter.getCommandType())) {
            mcpRegistry.preloadClientMcps(requestParameter.getCommandIdList());
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientModelNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName();
    }
}
