package com.linrun.agent.domain.agent.rag.retrieval;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 混合检索请求。
 */
@Data
@Builder
public class HybridRetrievalRequest {

    /** 租户隔离 */
    private String ownerId;

    /** 检索 query */
    private String query;

    /** 限定 doc_type（可空表示全部） */
    private List<String> docTypes;

    /** JSONB metadata 等值/列表过滤。 */
    private Map<String, Object> metadataFilters;

    /** 返回条数 */
    private int topK;

    /** 向量召回的最低相似度阈值 */
    private double scoreThreshold;

    /** 是否启用关键词召回（false 则只走向量） */
    private boolean keywordEnabled;
}
