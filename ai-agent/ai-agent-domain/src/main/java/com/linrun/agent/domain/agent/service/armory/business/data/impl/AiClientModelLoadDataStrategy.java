package com.linrun.agent.domain.agent.service.armory.business.data.impl;

import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.entity.ArmoryCommandEntity;
import com.linrun.agent.domain.agent.model.valobj.AiClientApiVO;
import com.linrun.agent.domain.agent.model.valobj.AiClientModelVO;
import com.linrun.agent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.linrun.agent.domain.agent.runtime.executor.AgentExecutorSupport;
import com.linrun.agent.domain.agent.service.armory.business.data.ILoadDataStrategy;
import com.linrun.agent.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;


@Slf4j
@Service("aiClientModelLoadDataStrategy")
public class AiClientModelLoadDataStrategy implements ILoadDataStrategy {

    @Resource
    private IAgentRepository repository;

    @Resource
    protected Executor threadPoolExecutor;

    @Override
    public void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {
        List<String> modelIdList = armoryCommandEntity.getCommandIdList();

        //查询api参数
        CompletableFuture<List<AiClientApiVO>> aiClientApiListFuture = AgentExecutorSupport.supplyAsync(threadPoolExecutor, "armoryModelApiLoad", () -> {
            log.info("查询配置数据(ai_client_api) {}", modelIdList);
            return repository.queryAiClientApiVOListByModelIds(modelIdList);
        });

        //查询对应的ai客户端参数
        CompletableFuture<List<AiClientModelVO>> aiClientModelListFuture = AgentExecutorSupport.supplyAsync(threadPoolExecutor, "armoryModelLoad", () -> {
            log.info("查询配置数据(ai_client_model) {}", modelIdList);
            return repository.AiClientModelVOByModelIds(modelIdList);
        });

        CompletableFuture.allOf(aiClientApiListFuture, aiClientModelListFuture).thenRun(() -> {
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_API.getDataName(), aiClientApiListFuture.join());
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName(), aiClientModelListFuture.join());
        }).join();

    }

}
