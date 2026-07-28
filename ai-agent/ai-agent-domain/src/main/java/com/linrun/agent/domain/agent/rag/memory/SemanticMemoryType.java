package com.linrun.agent.domain.agent.rag.memory;

/**
 * 三层记忆文档类型。
 *
 * <p>借鉴 dodo-agentx 的三层记忆设计：
 * <ul>
 *   <li>{@link #QA_PAIR} —— 每轮对话的 Q&A 对，原子记忆单元</li>
 *   <li>{@link #SESSION_SUMMARY} —— 单会话摘要，增量合并（1 旧摘要 + 新对话）</li>
 *   <li>{@link #CROSS_SUMMARY} —— 跨会话摘要，水位线触发（latest_qa_created_at）</li>
 * </ul>
 */
public enum SemanticMemoryType {

    QA_PAIR("qa_pair"),
    SESSION_SUMMARY("session_summary"),
    CROSS_SUMMARY("cross_summary");

    private final String dbValue;

    SemanticMemoryType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static SemanticMemoryType fromDbValue(String value) {
        for (SemanticMemoryType type : values()) {
            if (type.dbValue.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
