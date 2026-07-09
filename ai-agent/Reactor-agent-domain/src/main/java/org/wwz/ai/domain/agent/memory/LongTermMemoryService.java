package org.wwz.ai.domain.agent.memory;

import java.util.List;

/**
 * 长期跨会话记忆（三层记忆中的「长期/持久记忆」）。
 * 以 Qdrant 向量库按用户维度存储历史回合，新问题来时做语义召回，并按时间衰减实现"遗忘"。
 * 所有方法在 Qdrant 不可用或未启用时 fail-open（save 无副作用、recall 返回空）。
 */
public interface LongTermMemoryService {

    /**
     * 保存一次对话回合到长期记忆。
     */
    void save(MemoryTurn turn);

    /**
     * 按用户与当前问题语义召回历史片段（已按时间衰减重排、并排除当前会话近轮）。
     *
     * @return 召回的文本片段，若未启用/无命中/异常则返回空列表
     */
    List<String> recall(String ownerId, String currentSessionId, String query);
}
