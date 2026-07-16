package org.wwz.ai.domain.agent.memory;

import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;

/**
 * 跨会话结构化记忆。
 *
 * <p>memoryKey 是同一语义槽位的稳定键；相同 ownerId + memoryKey 使用稳定向量 ID upsert，
 * 从而使重试幂等。version 用于召回阶段对历史重复数据做确定性冲突消解。</p>
 */
@Value
@Builder(toBuilder = true)
public class LongTermMemoryEntry {

    String id;
    String ownerId;
    String sessionId;
    String requestId;
    LongTermMemoryType type;
    String memoryKey;
    String content;
    String source;
    @Builder.Default
    double confidence = 0.6d;
    @Builder.Default
    long version = 1L;
    Long createdAtEpochMillis;
    Long expiresAtEpochMillis;
    /** 仅在召回结果中使用，不持久化为事实本身。 */
    Double relevanceScore;

    public boolean isExpired(long nowEpochMillis) {
        return expiresAtEpochMillis != null
                && expiresAtEpochMillis > 0
                && expiresAtEpochMillis <= nowEpochMillis;
    }

    public String toPromptSnippet() {
        return "[" + (type == null ? LongTermMemoryType.FACT : type)
                + " source=" + StringUtils.defaultIfBlank(source, "unknown")
                + " confidence=" + String.format(java.util.Locale.ROOT, "%.2f", confidence)
                + " version=" + Math.max(1L, version) + "] "
                + StringUtils.defaultString(content);
    }
}
