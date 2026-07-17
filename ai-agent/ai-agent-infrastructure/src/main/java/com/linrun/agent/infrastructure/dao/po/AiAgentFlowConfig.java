package com.linrun.agent.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 角色 profile 的客户端与规程绑定。
 * @description 物理表沿用 ai_agent_flow_config，运行时语义仅为 Harness profile 输入
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentFlowConfig {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 客户端ID
     */
    private String clientId;

    /**
     * 客户端名称
     */
    private String clientName;

    /**
     * 客户端枚举
     */
    private String clientType;

    /**
     * 角色规程顺序
     */
    private Integer sequence;

    /**
     * 注入统一 Agent Loop 的角色规程提示词
     */
    private String stepPrompt;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
