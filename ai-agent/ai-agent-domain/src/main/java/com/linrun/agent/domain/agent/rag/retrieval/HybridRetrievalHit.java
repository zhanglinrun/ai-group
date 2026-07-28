package com.linrun.agent.domain.agent.rag.retrieval;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 混合检索单条结果。
 */
@Data
@Builder
public class HybridRetrievalHit {

    /** agent_semantic_memory.id */
    private String memoryId;

    /** 原文 */
    private String content;

    /** doc_type */
    private String docType;

    /** 会话 ID */
    private String conversationId;

    private Map<String, Object> metadata;

    /** 融合后的得分（RRF） */
    private double fusedScore;

    /** 向量原始得分（仅向量召回有） */
    private double vectorScore;

    /** 关键词原始得分（仅关键词召回有） */
    private double keywordScore;

    /** 命中来源：VECTOR | KEYWORD | BOTH */
    private String source;
}
