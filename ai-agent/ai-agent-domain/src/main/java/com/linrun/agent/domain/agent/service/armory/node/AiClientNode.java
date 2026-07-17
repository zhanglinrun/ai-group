package com.linrun.agent.domain.agent.service.armory.node;

import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.domain.agent.model.entity.ArmoryCommandEntity;
import com.linrun.agent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.linrun.agent.domain.agent.model.valobj.AiClientModelVO;
import com.linrun.agent.domain.agent.model.valobj.AiClientSystemPromptVO;
import com.linrun.agent.domain.agent.model.valobj.AiClientVO;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.service.runtime.AiClientRuntimeRegistry;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ai agent 客户端对话对象节点
 * 2025/7/19 09:17
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    @Resource
    private McpRegistry mcpRegistry;

    @Resource
    private ModelCatalogPort modelCatalogPort;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("armory node start node=client commandType={} commandIdCount={}",
                requestParameter == null ? null : requestParameter.getCommandType(),
                requestParameter == null || requestParameter.getCommandIdList() == null
                        ? 0 : requestParameter.getCommandIdList().size());

        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());

        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        for (AiClientVO aiClientVO : aiClientList) {
            // 1. 预设话术
            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            List<String> promptIdList = aiClientVO.getPromptIdList();
            for (String promptId : promptIdList) {
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap.get(promptId);
                defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
            }

            // 2. 对话模型
            ChatModel chatModel = aiClientRuntimeRegistry.getRequiredModel(aiClientVO.getModelId());

            // 3. MCP 服务
            List<ToolCallback> toolCallbacks = mcpRegistry.getToolCallbacksByMcpIds(aiClientVO.getMcpIdList());

            // 4. advisor 顾问角色
            List<Advisor> advisors = new ArrayList<>();
            List<String> advisorIdList = aiClientVO.getAdvisorIdList();
            if (advisorIdList != null) {
                for (String advisorId : advisorIdList) {
                    advisors.add(aiClientRuntimeRegistry.getRequiredAdvisor(advisorId));
                }
            }


            Advisor[] advisorArray = advisors.toArray(new Advisor[]{});

            // 6. 构建对话客户端
            ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultAdvisors(advisorArray);

            // fix 策略继续使用 Spring AI 原生 ToolCallback，但改成装配阶段一次性生成并缓存，避免请求期重复 listTools。
            if (!toolCallbacks.isEmpty()) {
                chatClientBuilder.defaultToolCallbacks(toolCallbacks);
            }

            ChatClient chatClient = chatClientBuilder.build();

            aiClientRuntimeRegistry.registerChatClient(aiClientVO.getClientId(), chatClient);

            // 组合客户端：为角色配置支持"用户请求级换模型"，
            // 复用同一 client 的系统提示 / advisor / 工具，仅替换底层模型，按 clientId::modelId 注册。
            // 仅对已装配 ChatModel 的启用模型生成组合。
            registerModelCombos(aiClientVO, chatClient, defaultSystem.toString(), advisorArray, toolCallbacks);
        }

        return router(requestParameter, dynamicContext);
    }

    /**
     * 为该 client 的每个"已装配 ChatModel 的启用模型"注册组合对话客户端（clientId::modelId）。
     * 主模型自身也登记组合键，便于选择主模型时命中同一实例。
     */
    private void registerModelCombos(AiClientVO aiClientVO,
                                     ChatClient primaryChatClient,
                                     String defaultSystem,
                                     Advisor[] advisorArray,
                                     List<ToolCallback> toolCallbacks) {
        String clientId = aiClientVO.getClientId();
        String primaryModelId = aiClientVO.getModelId();
        // 主模型组合键指向已构建好的主客户端
        aiClientRuntimeRegistry.registerChatClient(
                AiClientRuntimeRegistry.comboClientKey(clientId, primaryModelId), primaryChatClient);

        if (modelCatalogPort == null) {
            return;
        }
        List<AiClientModelVO> availableModels = modelCatalogPort.listAvailableModels();
        if (availableModels == null) {
            return;
        }
        for (AiClientModelVO modelVO : availableModels) {
            String modelId = modelVO == null ? null : modelVO.getModelId();
            if (modelId == null || modelId.equals(primaryModelId)) {
                continue;
            }
            ChatModel comboModel = aiClientRuntimeRegistry.findModel(modelId);
            if (comboModel == null) {
                // 该模型未装配 ChatModel（未随任何 client 加载），跳过；请求期回退默认模型
                continue;
            }
            ChatClient.Builder comboBuilder = ChatClient.builder(comboModel)
                    .defaultSystem(defaultSystem)
                    .defaultAdvisors(advisorArray);
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                comboBuilder.defaultToolCallbacks(toolCallbacks);
            }
            aiClientRuntimeRegistry.registerChatClient(
                    AiClientRuntimeRegistry.comboClientKey(clientId, modelId), comboBuilder.build());
        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }

}
