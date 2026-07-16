package org.wwz.ai.domain.agent.reactor.data.dto;


import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class VectorSaveReq {
    private String collectionName;
    private List<VectorData> dataList;
    /**
     * 需要在首次写入前确保存在的 keyword payload 索引。
     *
     * <p>为空时保持旧调用方行为；长期记忆等多租户场景可显式声明 ownerId 等过滤字段。</p>
     */
    private List<String> keywordIndexFields;

    @Data
    public static class VectorData {
        private String embeddingText;
        private String uuid;
        private Map<String, Object> payloads;
    }
}
