package org.wwz.ai.domain.agent.memory;

import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.data.dto.VectorRecallReq;
import org.wwz.ai.domain.agent.reactor.data.dto.VectorSaveReq;
import org.wwz.ai.domain.agent.reactor.service.VectorService;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.qdrant.client.ConditionFactory.matchKeyword;

/**
 * 结构化长期记忆实现：Qdrant 负责语义候选召回，本服务负责 owner 隔离、TTL、版本冲突消解和时间衰减。
 * 旧的 turn payload 仍可无迁移召回，并按 FACT 兼容读取。
 */
@Slf4j
@Service
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final String KIND_TURN = "turn";
    private static final String KIND_STRUCTURED = "structured-memory";
    private static final long RECALL_TIMEOUT_MILLIS = 3000L;
    private static final int MAX_EMBEDDING_TEXT_CHARS = 2000;
    private static final long FACT_TTL_MILLIS = 180L * 86_400_000L;
    private static final long STABLE_MEMORY_TTL_MILLIS = 365L * 86_400_000L;

    private final VectorService vectorService;
    private final ReactorConfig reactorConfig;

    public LongTermMemoryServiceImpl(VectorService vectorService, ReactorConfig reactorConfig) {
        this.vectorService = vectorService;
        this.reactorConfig = reactorConfig;
    }

    @Override
    public void save(MemoryTurn turn) {
        if (!isLongTermEnabled() || turn == null || StringUtils.isBlank(turn.ownerId())) {
            return;
        }
        MemoryAdmission admission = admitTurn(turn.query());
        if (admission == null) {
            log.debug("skip non-durable conversation turn, ownerId={}, requestId={}",
                    turn.ownerId(), turn.requestId());
            return;
        }
        long now = System.currentTimeMillis();
        String content = buildExplicitMemoryText(turn.query());
        if (StringUtils.isBlank(content)) {
            return;
        }
        LongTermMemoryEntry entry = LongTermMemoryEntry.builder()
                .ownerId(turn.ownerId())
                .sessionId(turn.sessionId())
                .requestId(turn.requestId())
                .type(admission.type())
                .memoryKey(admission.memoryKey())
                .content(content)
                .source("explicit-user-memory")
                .confidence(admission.confidence())
                // Explicit updates to the same semantic slot share one stable vector ID. The timestamp
                // is also persisted as a monotonic conflict-resolution version for legacy duplicates.
                .version(now)
                .createdAtEpochMillis(now)
                .expiresAtEpochMillis(now + defaultTtlMillis(admission.type()))
                .build();
        saveInternal(entry, KIND_TURN);
    }

    @Override
    public void save(LongTermMemoryEntry entry) {
        saveInternal(entry, KIND_STRUCTURED);
    }

    private void saveInternal(LongTermMemoryEntry candidate, String kind) {
        if (!isLongTermEnabled() || candidate == null || StringUtils.isBlank(candidate.getOwnerId())) {
            return;
        }
        LongTermMemoryEntry entry = normalizeEntry(candidate);
        if (StringUtils.isBlank(entry.getContent()) || entry.isExpired(System.currentTimeMillis())) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ownerId", entry.getOwnerId());
            payload.put("sessionId", StringUtils.defaultString(entry.getSessionId()));
            payload.put("requestId", StringUtils.defaultString(entry.getRequestId()));
            payload.put("kind", kind);
            payload.put("text", entry.getContent());
            payload.put("memoryId", entry.getId());
            payload.put("memoryKey", entry.getMemoryKey());
            payload.put("memoryType", entry.getType().name());
            payload.put("source", entry.getSource());
            payload.put("confidence", String.valueOf(entry.getConfidence()));
            payload.put("version", String.valueOf(entry.getVersion()));
            payload.put("createdAt", String.valueOf(entry.getCreatedAtEpochMillis()));
            payload.put("expiresAt", String.valueOf(entry.getExpiresAtEpochMillis()));
            // 保留旧字段供现有数据工具和查询兼容。
            payload.put("ts", String.valueOf(entry.getCreatedAtEpochMillis()));

            VectorSaveReq.VectorData data = new VectorSaveReq.VectorData();
            data.setEmbeddingText(entry.getContent());
            data.setPayloads(payload);
            // owner + memoryKey 生成稳定 ID：相同请求重试为 upsert，不制造重复记忆。
            data.setUuid(entry.getId());

            VectorSaveReq req = new VectorSaveReq();
            req.setCollectionName(reactorConfig.getLongTermMemoryCollection());
            req.setDataList(List.of(data));
            req.setKeywordIndexFields(List.of(
                    "ownerId", "memoryId", "memoryKey", "memoryType", "sessionId"));

            boolean ok = Boolean.TRUE.equals(vectorService.saveVector(req));
            if (!ok) {
                log.warn("long-term memory save returned false, ownerId={}, memoryKey={}",
                        entry.getOwnerId(), entry.getMemoryKey());
            }
        } catch (Exception e) {
            // fail-open：长期记忆写入失败不影响主链路
            log.warn("long-term memory save failed, ownerId={}, memoryKey={}",
                    entry.getOwnerId(), entry.getMemoryKey(), e);
        }
    }

    @Override
    public List<String> recall(String ownerId, String currentSessionId, String query) {
        return recallEntries(ownerId, currentSessionId, query).stream()
                .map(LongTermMemoryEntry::getContent)
                .toList();
    }

    @Override
    public List<LongTermMemoryEntry> recallEntries(String ownerId,
                                                   String currentSessionId,
                                                   String query) {
        if (!isLongTermEnabled() || StringUtils.isBlank(ownerId) || StringUtils.isBlank(query)) {
            return List.of();
        }
        int topK = resolveTopK();
        try {
            VectorRecallReq req = new VectorRecallReq();
            req.setCollectionName(reactorConfig.getLongTermMemoryCollection());
            req.setQuery(query);
            req.setKeywordFilterMap(Map.of("ownerId", ownerId));
            req.setScoreThreshold(reactorConfig.getLongTermMemoryScoreThreshold());
            req.setLimit(Math.max(topK * 3, topK));
            req.setTimeout(RECALL_TIMEOUT_MILLIS);

            List<Map<String, Object>> hits = vectorService.vectorRecall(req);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }

            long now = System.currentTimeMillis();
            double halfLifeDays = resolveHalfLifeDays();
            Map<String, ScoredMemory> newestByKey = new LinkedHashMap<>();
            for (Map<String, Object> hit : hits) {
                LongTermMemoryEntry entry = toEntry(hit, ownerId);
                if (entry == null || entry.isExpired(now)) {
                    continue;
                }
                if (StringUtils.isNotBlank(currentSessionId)
                        && StringUtils.equals(entry.getSessionId(), currentSessionId)) {
                    continue;
                }
                double rawScore = asDouble(hit.get("score"));
                long createdAt = valueOr(entry.getCreatedAtEpochMillis(), asLong(hit.get("ts")));
                double adjusted = rawScore * timeDecay(now, createdAt, halfLifeDays);
                LongTermMemoryEntry scoredEntry = entry.toBuilder().relevanceScore(adjusted).build();
                ScoredMemory scored = new ScoredMemory(scoredEntry, adjusted);
                newestByKey.merge(entry.getMemoryKey(), scored, this::preferNewerVersion);
            }

            return newestByKey.values().stream()
                    .sorted(Comparator.comparingDouble(ScoredMemory::adjustedScore).reversed())
                    .limit(topK)
                    .map(ScoredMemory::entry)
                    .toList();
        } catch (Exception e) {
            log.warn("long-term memory recall failed, ownerId={}", ownerId, e);
            return List.of();
        }
    }

    @Override
    public boolean delete(String ownerId, String memoryId) {
        if (!isLongTermEnabled() || StringUtils.isBlank(ownerId) || StringUtils.isBlank(memoryId)) {
            return false;
        }
        try {
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(matchKeyword("ownerId", ownerId))
                    .addMust(matchKeyword("memoryId", memoryId))
                    .build();
            return Boolean.TRUE.equals(vectorService.deleteVector(
                    reactorConfig.getLongTermMemoryCollection(), filter));
        } catch (Exception e) {
            log.warn("long-term memory delete failed, ownerId={}, memoryId={}", ownerId, memoryId, e);
            return false;
        }
    }

    private LongTermMemoryEntry normalizeEntry(LongTermMemoryEntry candidate) {
        long now = System.currentTimeMillis();
        LongTermMemoryType type = candidate.getType() == null ? LongTermMemoryType.FACT : candidate.getType();
        String content = truncateEmbeddingText(candidate.getContent());
        String memoryKey = StringUtils.defaultIfBlank(
                candidate.getMemoryKey(),
                "content:" + stableUuid(candidate.getOwnerId(), content)
        );
        long createdAt = valueOr(candidate.getCreatedAtEpochMillis(), now);
        long expiresAt = valueOr(candidate.getExpiresAtEpochMillis(), createdAt + defaultTtlMillis(type));
        String id = isUuid(candidate.getId())
                ? candidate.getId()
                : stableUuid(candidate.getOwnerId(), memoryKey);
        return candidate.toBuilder()
                .id(id)
                .type(type)
                .memoryKey(memoryKey)
                .content(content)
                .source(StringUtils.defaultIfBlank(candidate.getSource(), "explicit-memory"))
                .confidence(clampConfidence(candidate.getConfidence()))
                .version(Math.max(1L, candidate.getVersion()))
                .createdAtEpochMillis(createdAt)
                .expiresAtEpochMillis(expiresAt)
                .build();
    }

    private LongTermMemoryEntry toEntry(Map<String, Object> hit, String ownerId) {
        if (hit == null) {
            return null;
        }
        String content = asString(hit.get("text"));
        if (StringUtils.isBlank(content)) {
            return null;
        }
        String id = StringUtils.defaultIfBlank(asString(hit.get("memoryId")), asString(hit.get("_id")));
        if (StringUtils.isBlank(id)) {
            id = stableUuid(ownerId, content);
        }
        String memoryKey = StringUtils.defaultIfBlank(
                asString(hit.get("memoryKey")),
                "legacy:" + id
        );
        long createdAt = firstPositive(asLong(hit.get("createdAt")), asLong(hit.get("ts")));
        long expiresAt = asLong(hit.get("expiresAt"));
        return LongTermMemoryEntry.builder()
                .id(id)
                .ownerId(ownerId)
                .sessionId(asString(hit.get("sessionId")))
                .requestId(asString(hit.get("requestId")))
                .type(LongTermMemoryType.from(hit.get("memoryType")))
                .memoryKey(memoryKey)
                .content(content)
                .source(StringUtils.defaultIfBlank(asString(hit.get("source")), "legacy-turn"))
                .confidence(clampConfidence(defaultIfZero(asDouble(hit.get("confidence")), 0.5d)))
                .version(Math.max(1L, asLong(hit.get("version"))))
                .createdAtEpochMillis(createdAt > 0 ? createdAt : null)
                .expiresAtEpochMillis(expiresAt > 0 ? expiresAt : null)
                .build();
    }

    private ScoredMemory preferNewerVersion(ScoredMemory left, ScoredMemory right) {
        long leftVersion = left.entry().getVersion();
        long rightVersion = right.entry().getVersion();
        if (leftVersion != rightVersion) {
            return leftVersion > rightVersion ? left : right;
        }
        long leftCreated = valueOr(left.entry().getCreatedAtEpochMillis(), 0L);
        long rightCreated = valueOr(right.entry().getCreatedAtEpochMillis(), 0L);
        if (leftCreated != rightCreated) {
            return leftCreated > rightCreated ? left : right;
        }
        return left.adjustedScore() >= right.adjustedScore() ? left : right;
    }

    private boolean isLongTermEnabled() {
        return Boolean.TRUE.equals(reactorConfig.getMemoryEnabled())
                && Boolean.TRUE.equals(reactorConfig.getLongTermMemoryEnabled())
                && StringUtils.isNotBlank(reactorConfig.getLongTermMemoryCollection());
    }

    private int resolveTopK() {
        Integer configured = reactorConfig.getLongTermMemoryTopK();
        return configured == null || configured <= 0 ? 5 : configured;
    }

    private double resolveHalfLifeDays() {
        Integer configured = reactorConfig.getLongTermMemoryDecayHalfLifeDays();
        return configured == null || configured <= 0 ? 30d : configured;
    }

    private double timeDecay(long now, long ts, double halfLifeDays) {
        if (ts <= 0) {
            return 1d;
        }
        double ageDays = Math.max(0d, (now - ts) / 86_400_000d);
        return Math.pow(0.5d, ageDays / halfLifeDays);
    }

    /**
     * Deterministic admission policy for automatic conversation memory.
     *
     * <p>Ordinary questions and model-generated summaries are intentionally rejected. Durable memory
     * must originate from an explicit user statement; callers that own a stronger extraction/approval
     * flow can use {@link #save(LongTermMemoryEntry)} directly.</p>
     */
    private MemoryAdmission admitTurn(String query) {
        String normalized = StringUtils.defaultString(query)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }

        boolean explicit = containsAny(normalized,
                "请记住", "帮我记住", "记一下", "更新偏好", "以后请", "以后都", "每次都",
                "remember", "always remember", "from now on");
        boolean responseStyle = containsAny(normalized,
                "回答", "回复", "语言", "使用中文", "使用英文", "用中文", "用英文",
                "简洁", "详细", "结论", "格式", "风格", "语气",
                "answer", "response", "language", "respond in", "reply in", "concise", "detailed", "tone");
        boolean preferenceSignal = containsAny(normalized,
                "偏好", "喜欢", "不喜欢", "习惯", "以后请", "更新偏好",
                "prefer", "favorite", "from now on");
        boolean preference = (preferenceSignal && (explicit
                || (isFirstPersonStatement(normalized) && !looksLikeQuestion(normalized))))
                || (explicit && responseStyle);
        if (preference) {
            String key = responseStyle
                    ? "preference:response-style"
                    : "preference:explicit:" + shortStableKey(normalized);
            return new MemoryAdmission(LongTermMemoryType.PREFERENCE, key, 0.90d);
        }

        if (!explicit) {
            return null;
        }
        if (containsAny(normalized, "步骤", "流程", "sop", "操作规范", "procedure", "workflow")) {
            return new MemoryAdmission(
                    LongTermMemoryType.PROCEDURE,
                    "procedure:explicit:" + shortStableKey(normalized),
                    0.85d);
        }
        String factKey = resolveFactMemoryKey(normalized);
        return new MemoryAdmission(LongTermMemoryType.FACT, factKey, 0.80d);
    }

    private boolean isFirstPersonStatement(String value) {
        return containsAny(value, "我", "本人")
                || value.matches(".*\\b(i|i'm|i am|my|mine|me)\\b.*");
    }

    private boolean looksLikeQuestion(String value) {
        String normalized = StringUtils.defaultString(value).trim();
        if (normalized.endsWith("?") || normalized.endsWith("？")) {
            return true;
        }
        if (normalized.matches(".*[吗呢么嘛]$")) {
            return true;
        }
        if (normalized.matches(
                ".*(?:我|本人)\\s*(?:到底|究竟)?\\s*(?:是否|为什么|为何|怎么|如何|要不要|能不能|会不会).*")) {
            return true;
        }
        return normalized.startsWith("为什么")
                || normalized.startsWith("什么")
                || normalized.startsWith("怎么")
                || normalized.startsWith("如何")
                || normalized.startsWith("是否")
                || normalized.startsWith("你觉得")
                || normalized.matches("^(why|what|how|do|does|did|is|are|can|could|should|would)\\b.*");
    }

    private String resolveFactMemoryKey(String normalized) {
        List<String> profileKeys = new java.util.ArrayList<>();
        addProfileKeyIfMatched(profileKeys, "fact:user-name", normalized,
                "我叫", "我的名字", "名字是", "my name");
        addProfileKeyIfMatched(profileKeys, "fact:user-school", normalized,
                "学校", "就读", "毕业于", "my school", "study at", "studied at");
        addProfileKeyIfMatched(profileKeys, "fact:user-employer", normalized,
                "公司", "任职于", "就职于", "i work at", "employer", "company");
        addProfileKeyIfMatched(profileKeys, "fact:user-occupation", normalized,
                "职业", "岗位", "职位", "工作是", "my job", "occupation", "my role");
        addProfileKeyIfMatched(profileKeys, "fact:user-location", normalized,
                "所在地", "住在", "来自", "i live", "based in", "location");
        if (profileKeys.size() == 1) {
            return profileKeys.get(0);
        }
        if (profileKeys.size() > 1) {
            // 单条声明包含多个资料属性时不占用任何单属性槽位，避免后续局部更新误覆盖整条资料。
            return "fact:user-profile-composite:" + shortStableKey(normalized);
        }
        return "fact:explicit:" + shortStableKey(normalized);
    }

    private void addProfileKeyIfMatched(List<String> keys,
                                        String key,
                                        String value,
                                        String... candidates) {
        if (containsAny(value, candidates)) {
            keys.add(key);
        }
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String shortStableKey(String content) {
        return stableUuid("memory-slot", content).substring(0, 12);
    }

    private long defaultTtlMillis(LongTermMemoryType type) {
        return type == LongTermMemoryType.FACT ? FACT_TTL_MILLIS : STABLE_MEMORY_TTL_MILLIS;
    }

    private String buildExplicitMemoryText(String query) {
        String statement = StringUtils.defaultString(query).trim()
                .replaceFirst("(?i)^(请|帮我)?\\s*(记住|记一下|remember(?: that)?)\\s*[:：,，-]?\\s*", "")
                .replaceFirst("^(更新偏好|偏好)\\s*[:：,，-]?\\s*", "")
                .trim();
        return statement.isBlank() ? "" : truncateEmbeddingText("用户明确声明: " + statement);
    }

    private String truncateEmbeddingText(String text) {
        String normalized = StringUtils.defaultString(text).trim();
        if (normalized.length() <= MAX_EMBEDDING_TEXT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_EMBEDDING_TEXT_CHARS);
    }

    private String stableUuid(String ownerId, String key) {
        return UUID.nameUUIDFromBytes(
                (StringUtils.defaultString(ownerId) + "|" + StringUtils.defaultString(key))
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private boolean isUuid(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignore) {
            return false;
        }
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

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long valueOr(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long firstPositive(long first, long second) {
        return first > 0 ? first : Math.max(0L, second);
    }

    private double defaultIfZero(double value, double fallback) {
        return value > 0d ? value : fallback;
    }

    private double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private record ScoredMemory(LongTermMemoryEntry entry, double adjustedScore) {
    }

    private record MemoryAdmission(LongTermMemoryType type, String memoryKey, double confidence) {
    }
}
