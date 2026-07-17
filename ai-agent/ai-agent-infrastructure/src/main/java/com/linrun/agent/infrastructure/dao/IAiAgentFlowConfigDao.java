package com.linrun.agent.infrastructure.dao;

import com.linrun.agent.infrastructure.dao.po.AiAgentFlowConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 智能体-客户端关联表 DAO
 * @description 智能体-客户端关联表数据访问对象
 */
@Mapper
public interface IAiAgentFlowConfigDao {

    /**
     * 根据智能体ID查询关联配置列表
     * @param agentId 智能体ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByAgentId(String agentId);

}
