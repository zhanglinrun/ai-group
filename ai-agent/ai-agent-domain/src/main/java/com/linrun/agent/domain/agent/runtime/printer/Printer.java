package com.linrun.agent.domain.agent.runtime.printer;


import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;

public interface Printer {
    void send(AgentStreamEvent event);

    void close();

    /**
     * 下游是否已断开（如客户端关闭 SSE）。
     * 供 Agent 主循环感知断开后提前终止，避免断开后继续空跑烧 token/配额。
     * 默认返回 false，非 SSE 场景（如内部调用、测试）无需感知断开。
     */
    default boolean isAborted() {
        return false;
    }
}
