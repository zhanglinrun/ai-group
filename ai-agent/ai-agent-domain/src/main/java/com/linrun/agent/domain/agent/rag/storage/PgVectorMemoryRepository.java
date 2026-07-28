package com.linrun.agent.domain.agent.rag.storage;

import com.linrun.agent.domain.agent.reactor.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PostgreSQL + pgvector 语义记忆仓储。
 *
 * <p>单表 {@code agent_semantic_memory} 用 {@code doc_type} 区分三层记忆
 * （qa_pair / cross_summary / session_summary），向量列走 pgvector 余弦距离。
 * 关键词检索走 content 列的 trigram 索引，向量检索走 embedding 列的 HNSW 索引。
 *
 * <p>仅在显式配置 {@code spring.datasource.postgres.url} 时装配，无 PostgreSQL 环境不会启动失败。
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "spring.datasource.postgres", name = "url")
public class PgVectorMemoryRepository {

    private static final int EMBEDDING_DIMENSION = 1024;

    private final JdbcTemplate pgJdbcTemplate;
    private final EmbeddingService embeddingService;

    @Autowired
    public PgVectorMemoryRepository(@Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate,
                                    EmbeddingService embeddingService) {
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.embeddingService = embeddingService;
    }

    /**
     * 保存一条语义记忆（含向量）。
     *
     * @param id              稳定主键（owner+memoryKey 的 UUID），为空时自动生成
     * @param ownerId         租户隔离
     * @param docType         qa_pair | cross_summary | session_summary
     * @param content         原文（同时也是 embedding 输入）
     * @param metadata        附加元数据（JSONB）
     * @param conversationId  会话 ID（可空）
     * @return 是否成功
     */
    public boolean saveMemory(String id, String ownerId, String docType,
                              String content, Map<String, Object> metadata,
                              String conversationId) {
        return saveMemory(id, ownerId, docType, content, metadata, conversationId, null);
    }

    public boolean saveMemoryWithWatermark(String id, String ownerId, String docType,
                                           String content, Map<String, Object> metadata,
                                           String conversationId, Timestamp watermark) {
        if (watermark == null) {
            return false;
        }
        return saveMemory(id, ownerId, docType, content, metadata, conversationId, watermark);
    }

    private boolean saveMemory(String id, String ownerId, String docType,
                               String content, Map<String, Object> metadata,
                               String conversationId, Timestamp watermark) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(content) || StringUtils.isBlank(docType)) {
            return false;
        }
        String memoryId = StringUtils.isNotBlank(id) ? id : UUID.randomUUID().toString();
        List<Float> vector = embeddingService.getVector(content);
        if (CollectionUtils.isEmpty(vector)) {
            log.warn("pgvector save skipped: embedding empty ownerId={} docType={}", ownerId, docType);
            return false;
        }
        String vectorLiteral = toPgVectorLiteral(vector);
        String metadataJson = com.linrun.agent.types.common.JsonUtils.toJson(
                metadata == null ? Map.of() : metadata);
        try {
            pgJdbcTemplate.update(
                    "INSERT INTO agent_semantic_memory (id, owner_id, doc_type, content, embedding, metadata, conversation_id, created_at, latest_qa_created_at) " +
                            "VALUES (?, ?, ?, ?, ?::vector, ?::jsonb, ?, ?, ?) " +
                            "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding, " +
                            "metadata = EXCLUDED.metadata, conversation_id = EXCLUDED.conversation_id, " +
                            "latest_qa_created_at = EXCLUDED.latest_qa_created_at",
                    memoryId, ownerId, docType, content, vectorLiteral, metadataJson,
                    conversationId, Timestamp.from(Instant.now()), watermark);
            return true;
        } catch (Exception e) {
            log.warn("pgvector save failed ownerId={} docType={} memoryId={} errorType={}",
                    ownerId, docType, memoryId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 向量召回：用 query 的 embedding 做余弦近邻搜索。
     */
    public List<Map<String, Object>> recallByVector(String ownerId, String query,
                                                    List<String> docTypes, int topK,
                                                    double scoreThreshold) {
        return recallByVector(ownerId, query, docTypes, Map.of(), topK, scoreThreshold);
    }

    public List<Map<String, Object>> recallByVector(String ownerId, String query,
                                                    List<String> docTypes,
                                                    Map<String, Object> metadataFilters,
                                                    int topK, double scoreThreshold) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(query) || topK <= 0) {
            return List.of();
        }
        List<Float> vector = embeddingService.getVector(query);
        if (CollectionUtils.isEmpty(vector)) {
            return List.of();
        }
        String vectorLiteral = toPgVectorLiteral(vector);
        StringBuilder sql = new StringBuilder(
                "SELECT id, owner_id, doc_type, content, metadata::text AS metadata_json, conversation_id, created_at, " +
                        "1 - (embedding <=> ?::vector) AS score " +
                        "FROM agent_semantic_memory WHERE owner_id = ? AND embedding IS NOT NULL");
        List<Object> params = new ArrayList<>();
        params.add(vectorLiteral);
        params.add(ownerId);
        if (CollectionUtils.isNotEmpty(docTypes)) {
            sql.append(" AND doc_type IN (");
            sql.append(docTypes.stream().map(t -> "?").collect(Collectors.joining(",")));
            sql.append(")");
            params.addAll(docTypes);
        }
        appendMetadataFilters(sql, params, metadataFilters);
        sql.append(" AND 1 - (embedding <=> ?::vector) >= ? ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(vectorLiteral);
        params.add(scoreThreshold);
        params.add(vectorLiteral);
        params.add(topK);
        try {
            return pgJdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.warn("pgvector recall failed ownerId={} errorType={}", ownerId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 关键词召回：trigram 模糊匹配 content 列。
     */
    public List<Map<String, Object>> recallByKeyword(String ownerId, String query,
                                                      List<String> docTypes, int topK) {
        return recallByKeyword(ownerId, query, docTypes, Map.of(), topK);
    }

    public List<Map<String, Object>> recallByKeyword(String ownerId, String query,
                                                     List<String> docTypes,
                                                     Map<String, Object> metadataFilters,
                                                     int topK) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(query) || topK <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, owner_id, doc_type, content, metadata::text AS metadata_json, conversation_id, created_at, " +
                        "similarity(content, ?) AS score " +
                        "FROM agent_semantic_memory WHERE owner_id = ? AND (content ILIKE ? OR similarity(content, ?) > 0.1)");
        List<Object> params = new ArrayList<>();
        params.add(query);
        params.add(ownerId);
        params.add("%" + query + "%");
        params.add(query);
        if (CollectionUtils.isNotEmpty(docTypes)) {
            sql.append(" AND doc_type IN (");
            sql.append(docTypes.stream().map(t -> "?").collect(Collectors.joining(",")));
            sql.append(")");
            params.addAll(docTypes);
        }
        appendMetadataFilters(sql, params, metadataFilters);
        sql.append(" ORDER BY score DESC LIMIT ?");
        params.add(topK);
        try {
            return pgJdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.warn("pgvector keyword recall failed ownerId={} errorType={}", ownerId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 删除指定 owner 下的单条记忆。
     */
    public boolean deleteMemory(String ownerId, String memoryId) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(memoryId)) {
            return false;
        }
        try {
            int affected = pgJdbcTemplate.update(
                    "DELETE FROM agent_semantic_memory WHERE owner_id = ? AND id = ?",
                    ownerId, memoryId);
            return affected > 0;
        } catch (Exception e) {
            log.warn("pgvector delete failed ownerId={} memoryId={} errorType={}",
                    ownerId, memoryId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 删除指定 owner 下某 doc_type 的全部记忆。
     */
    public boolean deleteByDocType(String ownerId, String docType) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(docType)) {
            return false;
        }
        try {
            pgJdbcTemplate.update(
                    "DELETE FROM agent_semantic_memory WHERE owner_id = ? AND doc_type = ?",
                    ownerId, docType);
            return true;
        } catch (Exception e) {
            log.warn("pgvector deleteByDocType failed ownerId={} docType={} errorType={}",
                    ownerId, docType, e.getClass().getSimpleName());
            return false;
        }
    }

    public boolean deleteByMetadata(String ownerId, String docType, String key, String value) {
        if (StringUtils.isAnyBlank(ownerId, docType, key, value)) {
            return false;
        }
        try {
            pgJdbcTemplate.update(
                    "DELETE FROM agent_semantic_memory WHERE owner_id = ? AND doc_type = ? AND metadata ->> ? = ?",
                    ownerId, docType, key, value);
            return true;
        } catch (Exception e) {
            log.warn("pgvector deleteByMetadata failed ownerId={} docType={} key={} errorType={}",
                    ownerId, docType, key, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 查询指定 owner + doc_type 的全部记忆（按时间倒序）。
     */
    public List<Map<String, Object>> findByOwnerAndDocType(String ownerId, String docType, int limit) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(docType) || limit <= 0) {
            return List.of();
        }
        try {
            return pgJdbcTemplate.queryForList(
                    "SELECT id, owner_id, doc_type, content, metadata, conversation_id, created_at, latest_qa_created_at " +
                            "FROM agent_semantic_memory WHERE owner_id = ? AND doc_type = ? ORDER BY created_at DESC LIMIT ?",
                    ownerId, docType, limit);
        } catch (Exception e) {
            log.warn("pgvector findByOwnerAndDocType failed ownerId={} docType={} errorType={}",
                    ownerId, docType, e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ==================== 用户画像（agent_user_profile） ====================

    /**
     * Upsert 用户画像记忆（偏好/事实 key-value）。
     */
    public boolean saveUserProfile(String ownerId, String memoryKey, String memoryType,
                                    String content, double confidence, String source) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(memoryKey) || StringUtils.isBlank(content)) {
            return false;
        }
        try {
            pgJdbcTemplate.update(
                    "INSERT INTO agent_user_profile (owner_id, memory_key, memory_type, content, confidence, source, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (owner_id, memory_key) DO UPDATE SET " +
                            "memory_type = EXCLUDED.memory_type, content = EXCLUDED.content, " +
                            "confidence = EXCLUDED.confidence, source = EXCLUDED.source, updated_at = EXCLUDED.updated_at",
                    ownerId, memoryKey, StringUtils.defaultIfBlank(memoryType, "FACT"),
                    content, confidence, StringUtils.defaultIfBlank(source, "explicit-memory"),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return true;
        } catch (Exception e) {
            log.warn("pgvector saveUserProfile failed ownerId={} memoryKey={} errorType={}",
                    ownerId, memoryKey, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 查询用户全部画像记忆。
     */
    public List<Map<String, Object>> getUserProfile(String ownerId) {
        if (StringUtils.isBlank(ownerId)) {
            return List.of();
        }
        try {
            return pgJdbcTemplate.queryForList(
                    "SELECT owner_id, memory_key, memory_type, content, confidence, source, created_at, updated_at " +
                            "FROM agent_user_profile WHERE owner_id = ? ORDER BY updated_at DESC",
                    ownerId);
        } catch (Exception e) {
            log.warn("pgvector getUserProfile failed ownerId={} errorType={}", ownerId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 删除用户画像中的指定 key。
     */
    public boolean deleteUserProfileKey(String ownerId, String memoryKey) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(memoryKey)) {
            return false;
        }
        try {
            int affected = pgJdbcTemplate.update(
                    "DELETE FROM agent_user_profile WHERE owner_id = ? AND memory_key = ?",
                    ownerId, memoryKey);
            return affected > 0;
        } catch (Exception e) {
            log.warn("pgvector deleteUserProfileKey failed ownerId={} memoryKey={} errorType={}",
                    ownerId, memoryKey, e.getClass().getSimpleName());
            return false;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 把 List<Float> 转成 pgvector 字面量格式：[0.1,0.2,...]
     */
    private String toPgVectorLiteral(List<Float> vector) {
        if (vector.size() != EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException(
                    "embedding dimension must be " + EMBEDDING_DIMENSION + ", actual=" + vector.size());
        }
        StringBuilder sb = new StringBuilder(vector.size() * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(vector.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    public List<Map<String, Object>> findByOwnerDocTypeAndConversation(
            String ownerId, String docType, String conversationId, int limit) {
        if (StringUtils.isAnyBlank(ownerId, docType, conversationId) || limit <= 0) {
            return List.of();
        }
        try {
            return pgJdbcTemplate.queryForList(
                    "SELECT id, owner_id, doc_type, content, metadata, conversation_id, created_at, latest_qa_created_at " +
                            "FROM agent_semantic_memory WHERE owner_id = ? AND doc_type = ? AND conversation_id = ? " +
                            "ORDER BY created_at DESC LIMIT ?",
                    ownerId, docType, conversationId, limit);
        } catch (Exception e) {
            log.warn("pgvector findByOwnerDocTypeAndConversation failed ownerId={} docType={} errorType={}",
                    ownerId, docType, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private void appendMetadataFilters(StringBuilder sql, List<Object> params,
                                       Map<String, Object> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return;
        }
        metadataFilters.forEach((key, value) -> {
            if (StringUtils.isBlank(key) || value == null) {
                return;
            }
            if (value instanceof Iterable<?> values) {
                List<String> normalized = new ArrayList<>();
                values.forEach(item -> {
                    if (item != null) normalized.add(String.valueOf(item));
                });
                if (normalized.isEmpty()) return;
                sql.append(" AND metadata ->> ? IN (")
                        .append(normalized.stream().map(item -> "?").collect(Collectors.joining(",")))
                        .append(")");
                params.add(key);
                params.addAll(normalized);
                return;
            }
            sql.append(" AND metadata ->> ? = ?");
            params.add(key);
            params.add(String.valueOf(value));
        });
    }
}
