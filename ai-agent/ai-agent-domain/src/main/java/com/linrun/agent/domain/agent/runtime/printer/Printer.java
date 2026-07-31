package com.linrun.agent.domain.agent.runtime.printer;


import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;

public interface Printer {
    void send(AgentStreamEvent event);

    void close();

    /**
     * 下游执行是否已由非浏览器原因取消。
     * 浏览器 SSE 断线不会通过此信号取消 durable Run；显式取消由 P30
     * owner-scoped cancel intent 传播到 AgentContext。
     */
    default boolean isAborted() {
        return false;
    }
}
