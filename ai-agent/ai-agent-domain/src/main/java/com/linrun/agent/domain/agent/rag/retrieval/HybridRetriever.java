package com.linrun.agent.domain.agent.rag.retrieval;

import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器：向量召回（pgvector 余弦）+ 关键词召回（trigram），用 RRF 融合。
 *
 * <p>借鉴 dodo-agentx 的 HybridRetriever，但简化为 2 路（砍掉 text2image / text2page / query 改写）：
 * <ul>
 *   <li>向量路：embedding <=> 余弦距离，捕获语义相似</li>
 *   <li>关键词路：content ILIKE + trigram 索引，捕获精确术语匹配</li>
 *   <li>融合：Reciprocal Rank Fusion（1/(60+rank)），无需额外 LLM 调用，确定性强</li>
 * </ul>
 *
 * <p>RRF 是工业界常用的零参数融合方法（Elasticsearch 8.x 的 RRFRetriever 同款），
 * 比简单加权求和更鲁棒：不受两路得分尺度差异影响，只看排名。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "spring.datasource.postgres", name = "url")
public class HybridRetriever {

    /** RRF 平滑常数，60 是业界经验值（Elasticsearch / TREC 同款） */
    private static final int RRF_K = 60;

    private final PgVectorMemoryRepository memoryRepository;

    @Autowired
    public HybridRetriever(PgVectorMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /**
     * 执行混合检索。
     */
    public List<HybridRetrievalHit> retrieve(HybridRetrievalRequest request) {
        if (request == null || StringUtils.isBlank(request.getOwnerId())
                || StringUtils.isBlank(request.getQuery()) || request.getTopK() <= 0) {
            return List.of();
        }
        int topK = request.getTopK();
        int fetchK = Math.max(topK * 3, topK);

        List<Map<String, Object>> vectorHits = memoryRepository.recallByVector(
                request.getOwnerId(), request.getQuery(), request.getDocTypes(),
                request.getMetadataFilters(), fetchK, request.getScoreThreshold());

        List<Map<String, Object>> keywordHits = List.of();
        if (request.isKeywordEnabled()) {
            keywordHits = memoryRepository.recallByKeyword(
                    request.getOwnerId(), request.getQuery(), request.getDocTypes(),
                    request.getMetadataFilters(), fetchK);
        }

        List<HybridRetrievalHit> fused = fuse(vectorHits, keywordHits);
        List<HybridRetrievalHit> result = fused.stream()
                .sorted(Comparator.comparingDouble(HybridRetrievalHit::getFusedScore).reversed())
                .limit(topK)
                .toList();
        log.info("hybrid retrieve ownerId={} queryChars={} vectorHits={} keywordHits={} fused={} returned={}",
                request.getOwnerId(), request.getQuery().length(),
                vectorHits.size(), keywordHits.size(), fused.size(), result.size());
        return result;
    }

    /**
     * RRF 融合两路结果。
     */
    private List<HybridRetrievalHit> fuse(List<Map<String, Object>> vectorHits,
                                          List<Map<String, Object>> keywordHits) {
        Map<String, FuseAccumulator> acc = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            Map<String, Object> hit = vectorHits.get(rank);
            String id = asString(hit.get("id"));
            if (StringUtils.isBlank(id)) continue;
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            FuseAccumulator a = acc.computeIfAbsent(id, k -> new FuseAccumulator(
                    id, asString(hit.get("content")), asString(hit.get("doc_type")),
                    asString(hit.get("conversation_id")), metadata(hit)));
            a.vectorScore = asDouble(hit.get("score"));
            a.fusedScore += rrfScore;
            a.source = "VECTOR";
        }

        for (int rank = 0; rank < keywordHits.size(); rank++) {
            Map<String, Object> hit = keywordHits.get(rank);
            String id = asString(hit.get("id"));
            if (StringUtils.isBlank(id)) continue;
            double rrfScore = 1.0 / (RRF_K + rank + 1);
            FuseAccumulator a = acc.get(id);
            if (a == null) {
                a = new FuseAccumulator(id, asString(hit.get("content")),
                        asString(hit.get("doc_type")), asString(hit.get("conversation_id")), metadata(hit));
                acc.put(id, a);
            }
            a.keywordScore = asDouble(hit.get("score"));
            a.fusedScore += rrfScore;
            a.source = a.source.equals("VECTOR") ? "BOTH" : "KEYWORD";
        }

        List<HybridRetrievalHit> result = new ArrayList<>();
        for (FuseAccumulator a : acc.values()) {
            result.add(HybridRetrievalHit.builder()
                    .memoryId(a.id)
                    .content(a.content)
                    .docType(a.docType)
                    .conversationId(a.conversationId)
                    .metadata(a.metadata)
                    .fusedScore(a.fusedScore)
                    .vectorScore(a.vectorScore)
                    .keywordScore(a.keywordScore)
                    .source(a.source)
                    .build());
        }
        return result;
    }

    /** RRF 融合的可变累加器 */
    private static final class FuseAccumulator {
        final String id;
        final String content;
        final String docType;
        final String conversationId;
        final Map<String, Object> metadata;
        double fusedScore = 0d;
        double vectorScore = 0d;
        double keywordScore = 0d;
        String source = "";

        FuseAccumulator(String id, String content, String docType, String conversationId,
                        Map<String, Object> metadata) {
            this.id = id;
            this.content = content;
            this.docType = docType;
            this.conversationId = conversationId;
            this.metadata = metadata;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> hit) {
        Object raw = hit.get("metadata_json");
        if (raw == null) return Map.of();
        Map<String, Object> parsed = com.linrun.agent.types.common.JsonUtils.parseObject(
                String.valueOf(raw), Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0d : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
