package org.wwz.ai.domain.agent.runtime.printer;


import org.wwz.ai.domain.agent.runtime.enums.AgentType;

import java.util.Map;

public interface Printer {
    /**
     * 发送消息
     *
     * @param message 消息内容
     */

    void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal);

    /**
     * 发送带扩展结果字段的消息。
     * 主要用于 planner round 这类需要透传稳定标识的场景。
     */
    void send(String messageId,
              String messageType,
              Object message,
              Map<String, Object> extraResultMap,
              String digitalEmployee,
              Boolean isFinal);

    void send(String messageType, Object message);

    void send(String messageType, Object message, String digitalEmployee);

    void send(String messageId, String messageType, Object message, Boolean isFinal);

    /**
     * 发送带扩展结果字段的简化消息。
     */
    void sendWithResultMap(String messageId,
                           String messageType,
                           Object message,
                           Map<String, Object> extraResultMap,
                           Boolean isFinal);

    /**
     * 发送带扩展结果字段的最终消息。
     */
    void sendWithResultMap(String messageType, Object message, Map<String, Object> extraResultMap);

    void close();

    void updateAgentType(AgentType agentType);

    /**
     * 下游是否已断开（如客户端关闭 SSE）。
     * 供 Agent 主循环感知断开后提前终止，避免断开后继续空跑烧 token/配额。
     * 默认返回 false，非 SSE 场景（如内部调用、测试）无需感知断开。
     */
    default boolean isAborted() {
        return false;
    }
}
