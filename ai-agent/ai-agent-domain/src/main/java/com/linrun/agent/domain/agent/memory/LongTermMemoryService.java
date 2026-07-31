package com.linrun.agent.domain.agent.memory;

import java.util.List;

/**
 * 长期跨会话记忆（三层记忆中的「长期/持久记忆」）。
 * PostgreSQL 按用户维度保存普通回合和结构化画像，新问题来时做混合召回并按时间衰减实现"遗忘"。
 */
public interface LongTermMemoryService {

    /**
     * 保存一次对话回合到长期记忆。
     */
    void save(MemoryTurn turn);

    /**
     * 保存显式结构化记忆。默认实现保持第三方/测试替身的向后兼容。
     */
    default void save(LongTermMemoryEntry entry) {
        // compatibility no-op
    }

    /**
     * 按用户与当前问题语义召回历史片段（已按时间衰减重排、并排除当前会话近轮）。
     *
     * @return 召回的文本片段，若未启用/无命中/异常则返回空列表
     */
    List<String> recall(String ownerId, String currentSessionId, String query);

    /**
     * 召回带类型、来源、置信度、版本和 TTL 的结构化条目。
     */
    default List<LongTermMemoryEntry> recallEntries(String ownerId,
                                                    String currentSessionId,
                                                    String query) {
        return List.of();
    }

    /**
     * 按 owner 边界删除一条结构化记忆。
     */
    default boolean delete(String ownerId, String memoryId) {
        return false;
    }

    /** Returns the owner-scoped consent state. Missing preference means disabled. */
    default LongTermMemoryPreference preference(String ownerId) {
        return LongTermMemoryPreference.disabled(ownerId);
    }

    /** Updates the owner-scoped consent and retention state. */
    default LongTermMemoryPreference updatePreference(LongTermMemoryPreference preference) {
        return preference == null ? LongTermMemoryPreference.disabled(null) : preference.normalized();
    }

    /** Lists owner-visible long-term entries without using them as prompt context. */
    default List<LongTermMemoryEntry> listEntries(String ownerId, int limit) {
        return List.of();
    }

    /** Removes entries whose durable retention timestamp has elapsed. */
    default int purgeExpired(String ownerId) {
        return 0;
    }
}
