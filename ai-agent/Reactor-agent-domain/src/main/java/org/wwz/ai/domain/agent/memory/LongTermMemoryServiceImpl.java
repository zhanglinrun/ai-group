package org.wwz.ai.domain.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.data.dto.VectorRecallReq;
import org.wwz.ai.domain.agent.reactor.data.dto.VectorSaveReq;
import org.wwz.ai.domain.agent.reactor.service.VectorService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期跨会话记忆实现：复用既有 EmbeddingService/VectorService/Qdrant 通路，
 * 按 ownerId 维度存取对话回合，召回时叠加时间衰减实现"遗忘"。全链路 fail-open。
 */
@Slf4j
@Service
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final String KIND_TURN = "turn";
    private static final long RECALL_TIMEOUT_MILLIS = 3000L;
    private static final int MAX_EMBEDDING_TEXT_CHARS = 2000;

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
        String embeddingText = buildEmbeddingText(turn.query(), turn.answerSummary());
        if (StringUtils.isBlank(embeddingText)) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ownerId", turn.ownerId());
            payload.put("sessionId", StringUtils.defaultString(turn.sessionId()));
            payload.put("requestId", StringUtils.defaultString(turn.requestId()));
            payload.put("kind", KIND_TURN);
            payload.put("text", embeddingText);
            payload.put("ts", String.valueOf(System.currentTimeMillis()));

            VectorSaveReq.VectorData data = new VectorSaveReq.VectorData();
            data.setEmbeddingText(embeddingText);
            data.setPayloads(payload);
            data.setUuid(UUID.randomUUID().toString());

            VectorSaveReq req = new VectorSaveReq();
            req.setCollectionName(reactorConfig.getLongTermMemoryCollection());
            req.setDataList(List.of(data));

            boolean ok = Boolean.TRUE.equals(vectorService.saveVector(req));
            if (!ok) {
                log.warn("long-term memory save returned false, ownerId={}, sessionId={}", turn.ownerId(), turn.sessionId());
            }
        } catch (Exception e) {
            // fail-open：长期记忆写入失败不影响主链路
            log.warn("long-term memory save failed, ownerId={}, sessionId={}", turn.ownerId(), turn.sessionId(), e);
        }
    }

    @Override
    public List<String> recall(String ownerId, String currentSessionId, String query) {
        if (!isLongTermEnabled() || StringUtils.isBlank(ownerId) || StringUtils.isBlank(query)) {
            return List.of();
        }
        int topK = resolveTopK();
        try {
            VectorRecallReq req = new VectorRecallReq();
            req.setCollectionName(reactorConfig.getLongTermMemoryCollection());
            req.setQuery(query);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("ownerId", ownerId);
            req.setKeywordFilterMap(filter);
            req.setScoreThreshold(reactorConfig.getLongTermMemoryScoreThreshold());
            // 多取一些候选，交给时间衰减重排后再截断 topK
            req.setLimit(Math.max(topK * 2, topK));
            req.setTimeout(RECALL_TIMEOUT_MILLIS);

            List<Map<String, Object>> hits = vectorService.vectorRecall(req);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }

            long now = System.currentTimeMillis();
            double halfLifeDays = resolveHalfLifeDays();
            List<ScoredMemory> scored = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                if (hit == null) {
                    continue;
                }
                String sessionId = asString(hit.get("sessionId"));
                // 当前会话近轮由中期记忆覆盖，长期召回排除自身会话避免重复
                if (StringUtils.isNotBlank(currentSessionId) && StringUtils.equals(sessionId, currentSessionId)) {
                    continue;
                }
                String text = asString(hit.get("text"));
                if (StringUtils.isBlank(text)) {
                    continue;
                }
                double rawScore = asDouble(hit.get("score"));
                double adjusted = rawScore * timeDecay(now, asLong(hit.get("ts")), halfLifeDays);
                scored.add(new ScoredMemory(text, adjusted));
            }

            return scored.stream()
                    .sorted(Comparator.comparingDouble(ScoredMemory::adjustedScore).reversed())
                    .limit(topK)
                    .map(ScoredMemory::text)
                    .toList();
        } catch (Exception e) {
            log.warn("long-term memory recall failed, ownerId={}", ownerId, e);
            return List.of();
        }
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

    private String buildEmbeddingText(String query, String answerSummary) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.isNotBlank(query)) {
            builder.append("用户: ").append(query.trim());
        }
        if (StringUtils.isNotBlank(answerSummary)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("结论: ").append(answerSummary.trim());
        }
        String text = builder.toString();
        if (text.length() > MAX_EMBEDDING_TEXT_CHARS) {
            text = text.substring(0, MAX_EMBEDDING_TEXT_CHARS);
        }
        return text;
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

    private record ScoredMemory(String text, double adjustedScore) {
    }
}
