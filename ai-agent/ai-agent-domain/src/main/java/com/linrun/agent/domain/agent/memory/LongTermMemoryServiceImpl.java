package com.linrun.agent.domain.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalHit;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalRequest;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.types.common.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PostgreSQL long-term-memory entry point. Only an explicit user instruction
 * may create durable profile memory; ordinary Q&A remains run/session context
 * and is never promoted automatically.
 */
@Slf4j
@Service
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final String DOC_QA_PAIR = "qa_pair";
    private static final int MAX_EMBEDDING_TEXT_CHARS = 2000;
    private static final long FACT_TTL_MILLIS = 180L * 86_400_000L;
    private static final long STABLE_MEMORY_TTL_MILLIS = 365L * 86_400_000L;

    private final PgVectorMemoryRepository memoryRepository;
    private final HybridRetriever hybridRetriever;
    private final ReactorConfig reactorConfig;

    @Autowired(required = false)
    private UserMemoryPreferenceService userMemoryPreferenceService;

    public LongTermMemoryServiceImpl(ObjectProvider<PgVectorMemoryRepository> memoryRepository,
                                     ObjectProvider<HybridRetriever> hybridRetriever,
                                     ReactorConfig reactorConfig) {
        this.memoryRepository = memoryRepository.getIfAvailable();
        this.hybridRetriever = hybridRetriever.getIfAvailable();
        this.reactorConfig = reactorConfig;
    }

    @Override
    public void save(MemoryTurn turn) {
        if (turn == null || StringUtils.isBlank(turn.ownerId()) || !isLongTermEnabled(turn.ownerId())) {
            return;
        }
        long now = System.currentTimeMillis();
        MemoryAdmission admission = admitTurn(turn.query());
        if (admission == null) {
            return;
        }
        String content = buildExplicitMemoryText(turn.query());
        if (StringUtils.isBlank(content)) return;
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
        saveProfile(entry);
    }

    @Override
    public void save(LongTermMemoryEntry entry) {
        saveProfile(normalizeEntry(entry));
    }

    private void saveProfile(LongTermMemoryEntry entry) {
        if (memoryRepository == null || entry == null
                || StringUtils.isBlank(entry.getOwnerId())) {
            return;
        }
        if (!isLongTermEnabled(entry.getOwnerId())) {
            return;
        }
        if (StringUtils.isBlank(entry.getContent()) || entry.isExpired(System.currentTimeMillis())) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long retentionExpiry = now + retentionMillis(preference(entry.getOwnerId()).retentionDays());
            long effectiveExpiry = entry.getExpiresAtEpochMillis() == null
                    ? retentionExpiry : Math.min(entry.getExpiresAtEpochMillis(), retentionExpiry);
            boolean ok = memoryRepository.saveUserProfile(
                    entry.getOwnerId(), entry.getMemoryKey(), entry.getType().name(),
                    entry.getContent(), entry.getConfidence(), entry.getSource(),
                    Instant.ofEpochMilli(effectiveExpiry));
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
        if (!isLongTermEnabled(ownerId) || memoryRepository == null
                || StringUtils.isBlank(ownerId) || StringUtils.isBlank(query)) {
            return List.of();
        }
        int topK = resolveTopK();
        try {
            List<LongTermMemoryEntry> profileEntries = memoryRepository.getUserProfile(ownerId).stream()
                    .map(row -> profileEntry(ownerId, row))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<HybridRetrievalHit> hits = hybridRetriever == null ? List.of() : hybridRetriever.retrieve(
                    HybridRetrievalRequest.builder()
                            .ownerId(ownerId)
                            .query(query)
                            .docTypes(List.of("session_summary", "cross_summary"))
                            .metadataFilters(Map.of())
                            .topK(Math.max(topK * 3, topK))
                            .scoreThreshold(reactorConfig.getLongTermMemoryScoreThreshold())
                            .keywordEnabled(true)
                            .build());

            long now = System.currentTimeMillis();
            double halfLifeDays = resolveHalfLifeDays();
            Map<String, ScoredMemory> newestByKey = new LinkedHashMap<>();
            for (LongTermMemoryEntry entry : profileEntries) {
                newestByKey.put(entry.getMemoryKey(), new ScoredMemory(entry, 1d));
            }
            for (HybridRetrievalHit hit : hits) {
                LongTermMemoryEntry entry = toEntry(hit, ownerId);
                if (entry == null || entry.isExpired(now)) {
                    continue;
                }
                if (DOC_QA_PAIR.equals(hit.getDocType()) && StringUtils.isNotBlank(currentSessionId)
                        && StringUtils.equals(entry.getSessionId(), currentSessionId)) {
                    continue;
                }
                double rawScore = hit.getFusedScore();
                long createdAt = valueOr(entry.getCreatedAtEpochMillis(), 0L);
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
        if (!isLongTermFeatureAvailable() || memoryRepository == null
                || StringUtils.isBlank(ownerId) || StringUtils.isBlank(memoryId)) {
            return false;
        }
        try {
            if (memoryRepository.deleteMemory(ownerId, memoryId)) return true;
            for (Map<String, Object> row : memoryRepository.getUserProfile(ownerId)) {
                String key = asString(row.get("memory_key"));
                if (memoryId.equals(stableUuid(ownerId, key))) {
                    return memoryRepository.deleteUserProfileKey(ownerId, key);
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("long-term memory delete failed, ownerId={}, memoryId={}", ownerId, memoryId, e);
            return false;
        }
    }

    @Override
    public LongTermMemoryPreference preference(String ownerId) {
        if (StringUtils.isBlank(ownerId)) {
            return LongTermMemoryPreference.disabled(ownerId);
        }
        if (userMemoryPreferenceService == null) {
            return isLongTermFeatureAvailable()
                    ? new LongTermMemoryPreference(ownerId, true,
                    LongTermMemoryPreference.DEFAULT_RETENTION_DAYS, null)
                    : LongTermMemoryPreference.disabled(ownerId);
        }
        return userMemoryPreferenceService.current(ownerId);
    }

    @Override
    public LongTermMemoryPreference updatePreference(LongTermMemoryPreference preference) {
        if (preference == null) {
            throw new IllegalArgumentException("memory preference must not be null");
        }
        if (!isLongTermFeatureAvailable() || userMemoryPreferenceService == null) {
            throw new IllegalStateException("long-term memory is unavailable");
        }
        return userMemoryPreferenceService.update(preference);
    }

    @Override
    public List<LongTermMemoryEntry> listEntries(String ownerId, int limit) {
        if (!isLongTermFeatureAvailable() || memoryRepository == null || StringUtils.isBlank(ownerId)) {
            return List.of();
        }
        purgeExpired(ownerId);
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        List<LongTermMemoryEntry> entries = new ArrayList<>();
        for (Map<String, Object> profile : memoryRepository.getUserProfile(ownerId)) {
            LongTermMemoryEntry entry = profileEntry(ownerId, profile);
            if (entry != null) {
                entries.add(entry);
            }
        }
        for (Map<String, Object> semantic : memoryRepository.findMemoriesByOwner(ownerId, boundedLimit)) {
            LongTermMemoryEntry entry = semanticEntry(ownerId, semantic);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries.stream()
                .filter(entry -> !entry.isExpired(System.currentTimeMillis()))
                .sorted(Comparator.comparing(entry -> valueOr(entry.getCreatedAtEpochMillis(), 0L), Comparator.reverseOrder()))
                .limit(boundedLimit)
                .toList();
    }

    @Override
    public int purgeExpired(String ownerId) {
        if (memoryRepository == null || StringUtils.isBlank(ownerId)) {
            return 0;
        }
        try {
            return memoryRepository.purgeExpired(ownerId);
        } catch (Exception e) {
            log.warn("long-term memory expiry purge failed ownerId={} errorType={}",
                    ownerId, e.getClass().getSimpleName());
            return 0;
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

    private LongTermMemoryEntry toEntry(HybridRetrievalHit hit, String ownerId) {
        if (hit == null) {
            return null;
        }
        Map<String, Object> metadata = hit.getMetadata() == null ? Map.of() : hit.getMetadata();
        String content = hit.getContent();
        if (StringUtils.isBlank(content)) {
            return null;
        }
        String id = hit.getMemoryId();
        if (StringUtils.isBlank(id)) {
            id = stableUuid(ownerId, content);
        }
        String memoryKey = StringUtils.defaultIfBlank(
                asString(metadata.get("memoryKey")),
                "legacy:" + id
        );
        long createdAt = asLong(metadata.get("createdAt"));
        long expiresAt = asLong(metadata.get("expiresAt"));
        return LongTermMemoryEntry.builder()
                .id(id)
                .ownerId(ownerId)
                .sessionId(hit.getConversationId())
                .requestId(asString(metadata.get("requestId")))
                .type(LongTermMemoryType.from(metadata.get("memoryType")))
                .memoryKey(memoryKey)
                .content(content)
                .source(StringUtils.defaultIfBlank(asString(metadata.get("source")), hit.getDocType()))
                .confidence(clampConfidence(defaultIfZero(asDouble(metadata.get("confidence")), 0.5d)))
                .version(Math.max(1L, asLong(metadata.get("version"))))
                .createdAtEpochMillis(createdAt > 0 ? createdAt : null)
                .expiresAtEpochMillis(expiresAt > 0 ? expiresAt : null)
                .build();
    }

    private LongTermMemoryEntry profileEntry(String ownerId, Map<String, Object> row) {
        String key = asString(row.get("memory_key"));
        String content = asString(row.get("content"));
        if (StringUtils.isAnyBlank(key, content)) return null;
        return LongTermMemoryEntry.builder()
                .id(stableUuid(ownerId, key))
                .ownerId(ownerId)
                .type(LongTermMemoryType.from(row.get("memory_type")))
                .memoryKey(key)
                .content(content)
                .source(asString(row.get("source")))
                .confidence(clampConfidence(asDouble(row.get("confidence"))))
                .version(1L)
                .createdAtEpochMillis(asEpochMillis(row.get("created_at")))
                .expiresAtEpochMillis(asEpochMillis(row.get("expires_at")))
                .build();
    }

    private LongTermMemoryEntry semanticEntry(String ownerId, Map<String, Object> row) {
        String id = asString(row.get("id"));
        String content = asString(row.get("content"));
        if (StringUtils.isAnyBlank(id, content)) {
            return null;
        }
        Map<String, Object> metadata = metadata(row.get("metadata_json"));
        long createdAt = firstPositive(asLong(metadata.get("createdAt")), asEpochMillis(row.get("created_at")));
        long expiresAt = firstPositive(asLong(metadata.get("expiresAt")), asEpochMillis(row.get("expires_at")));
        return LongTermMemoryEntry.builder()
                .id(id)
                .ownerId(ownerId)
                .sessionId(asString(row.get("conversation_id")))
                .requestId(asString(metadata.get("requestId")))
                .type(LongTermMemoryType.from(metadata.get("memoryType")))
                .memoryKey(StringUtils.defaultIfBlank(asString(metadata.get("memoryKey")), "semantic:" + id))
                .content(content)
                .source(StringUtils.defaultIfBlank(asString(metadata.get("source")), asString(row.get("doc_type"))))
                .confidence(clampConfidence(defaultIfZero(asDouble(metadata.get("confidence")), 0.5d)))
                .version(Math.max(1L, asLong(metadata.get("version"))))
                .createdAtEpochMillis(createdAt > 0L ? createdAt : null)
                .expiresAtEpochMillis(expiresAt > 0L ? expiresAt : null)
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

    private boolean isLongTermEnabled(String ownerId) {
        return isLongTermFeatureAvailable() && preference(ownerId).enabled();
    }

    private boolean isLongTermFeatureAvailable() {
        return Boolean.TRUE.equals(reactorConfig.getMemoryEnabled())
                && Boolean.TRUE.equals(reactorConfig.getLongTermMemoryEnabled())
                && memoryRepository != null;
    }

    private long retentionMillis(int retentionDays) {
        int bounded = Math.max(LongTermMemoryPreference.MIN_RETENTION_DAYS,
                Math.min(LongTermMemoryPreference.MAX_RETENTION_DAYS, retentionDays));
        return bounded * 86_400_000L;
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

    private long asEpochMillis(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toEpochMilli();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        return asLong(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        if (value == null) {
            return Map.of();
        }
        try {
            return JsonUtils.mapper().readValue(String.valueOf(value), LinkedHashMap.class);
        } catch (Exception ignored) {
            return Map.of();
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
