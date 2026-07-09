package org.wwz.ai.domain.agent.memory;

/**
 * 组装历史记忆注入块所需的查询上下文。
 *
 * @param ownerId          登录用户ID（用于长期记忆按用户隔离召回）
 * @param sessionId        当前会话ID（用于中期会话记忆重建）
 * @param currentRequestId 当前请求ID（重建历史时排除当前轮）
 * @param query            当前用户问题（用于长期记忆语义召回）
 */
public record MemoryQuery(String ownerId,
                          String sessionId,
                          String currentRequestId,
                          String query) {
}
