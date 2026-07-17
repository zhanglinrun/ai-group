package com.linrun.agent.domain.agent.memory;

/**
 * 对话记忆管理器：统一编排三层记忆的读（组装注入块）与写（回合落库）。
 *
 * <ul>
 *   <li>短期：单次 run 内的工作记忆，由 Agent 运行时自身持有，不在此编排。</li>
 *   <li>中期：会话级历史（近 K 轮原文 + 更早轮次摘要压缩），来自执行账本。</li>
 *   <li>长期：跨会话向量记忆，按用户召回并叠加时间衰减遗忘。</li>
 * </ul>
 *
 * 所有产品请求都由统一 Agent Loop 使用这一历史注入和落库入口。
 */
public interface ConversationMemoryManager {

    /**
     * 组装注入到 prompt 的历史记忆块：长期召回段 + 中期会话段。
     */
    String assembleHistoryBlock(MemoryQuery query);

    /**
     * 回合结束后异步落库到长期记忆（fail-open，不阻塞主链路/SSE）。
     */
    void persistTurnAsync(MemoryTurn turn);
}
