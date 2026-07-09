package org.wwz.ai.domain.agent.memory;

/**
 * 一次对话回合的记忆落库素材。
 *
 * @param ownerId       登录用户ID
 * @param sessionId     会话ID
 * @param requestId     请求ID（ReAct/Plan 模式可据此从账本回读 final_summary）
 * @param query         用户问题
 * @param answerSummary 本轮结论/答复摘要；chat 模式直接传答复内容，
 *                      ReAct/Plan 模式可留空由记忆管理器从执行账本解析
 */
public record MemoryTurn(String ownerId,
                         String sessionId,
                         String requestId,
                         String query,
                         String answerSummary) {
}
