package com.linrun.agent.domain.agent.runtime.enums;

/**
 * 智能体类型
 */
public enum AgentType {
    AGENT_LOOP(5);

    private final Integer value;

    AgentType(Integer value) {
        this.value = value;
    }

    public Integer getValue() {
        return value;
    }

}
