package com.linrun.agent.domain.agent.runtime.tool.common;

import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorSaveReq;
import com.linrun.agent.domain.agent.reactor.service.VectorService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 会话附件级大文本 RAG：切块 → 向量化 → 按 question 语义召回。
 * 不引入独立知识库产品面，隔离键仅为 sessionId + fileName。
 */
@Slf4j
public final class SessionFileRagService {

    private static final int DEFAULT_CHUNK_CHARS = 800;
    private static final int DEFAULT_OVERLAP_CHARS = 120;
    private static final int DEFAULT_TOP_K = 8;
    private static final float DEFAULT_SCORE_THRESHOLD = 0.25f;
    private static final int MAX_CHUNKS_PER_FILE = 200;
    private static final int MAX_RESULT_CHARS = 24_000;
    private static final long RECALL_TIMEOUT_MILLIS = 8_000L;

    private SessionFileRagService() {
    }

    public static String analyze(VectorService vectorService,
                                 ReactorConfig reactorConfig,
                                 String sessionId,
                                 String fileName,
                                 String question,
                                 String fullText) {
        if (vectorService == null) {
            return truncateForFallback(fullText);
        }
        if (StringUtils.isAnyBlank(sessionId, fileName, question, fullText)) {
            return "会话附件 RAG 参数不完整，无法检索。";
        }

        String collectionName = resolveCollection(reactorConfig);
        List<String> chunks = splitIntoChunks(
                fullText,
                resolveChunkChars(reactorConfig),
                resolveOverlapChars(reactorConfig));
        if (chunks.isEmpty()) {
            return "文件内容为空: " + fileName;
        }

        boolean indexed = indexChunks(vectorService, collectionName, sessionId, fileName, chunks);
        if (!indexed) {
            log.warn("session file RAG index failed, fall back to truncate sessionId={} fileName={}",
                    sessionId, fileName);
            return truncateForFallback(fullText);
        }

        List<Map<String, Object>> hits = recall(
                vectorService,
                reactorConfig,
                collectionName,
                sessionId,
                fileName,
                question);
        if (hits == null || hits.isEmpty()) {
            log.warn("session file RAG recall empty, fall back to truncate sessionId={} fileName={}",
                    sessionId, fileName);
            return truncateForFallback(fullText);
        }
        return formatHits(fileName, question, hits);
    }

    public static List<String> splitIntoChunks(String text, int chunkChars, int overlapChars) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeChunk = Math.max(200, chunkChars);
        int safeOverlap = Math.max(0, Math.min(overlapChars, safeChunk / 2));
        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        int length = normalized.length();
        while (cursor < length && chunks.size() < MAX_CHUNKS_PER_FILE) {
            int end = Math.min(length, cursor + safeChunk);
            if (end < length) {
                int breakAt = findBreak(normalized, cursor, end);
                if (breakAt > cursor + safeChunk / 2) {
                    end = breakAt;
                }
            }
            String piece = normalized.substring(cursor, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= length) {
                break;
            }
            cursor = Math.max(cursor + 1, end - safeOverlap);
        }
        return chunks;
    }

    private static int findBreak(String text, int start, int end) {
        for (int index = end; index > start; index--) {
            char current = text.charAt(index - 1);
            if (current == '\n' || current == '。' || current == '.' || current == '!' || current == '?' || current == '；') {
                return index;
            }
        }
        return end;
    }

    private static boolean indexChunks(VectorService vectorService,
                                       String collectionName,
                                       String sessionId,
                                       String fileName,
                                       List<String> chunks) {
        List<VectorSaveReq.VectorData> dataList = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            String chunkText = chunks.get(index);
            VectorSaveReq.VectorData data = new VectorSaveReq.VectorData();
            data.setUuid(stableChunkId(sessionId, fileName, index, chunkText));
            data.setEmbeddingText(chunkText);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("fileName", fileName);
            payload.put("chunkIndex", String.valueOf(index));
            payload.put("text", chunkText);
            payload.put("kind", "session-file-chunk");
            data.setPayloads(payload);
            dataList.add(data);
        }

        VectorSaveReq request = new VectorSaveReq();
        request.setCollectionName(collectionName);
        request.setDataList(dataList);
        request.setKeywordIndexFields(List.of("sessionId", "fileName", "kind"));
        return Boolean.TRUE.equals(vectorService.saveVector(request));
    }

    private static List<Map<String, Object>> recall(VectorService vectorService,
                                                    ReactorConfig reactorConfig,
                                                    String collectionName,
                                                    String sessionId,
                                                    String fileName,
                                                    String question) {
        VectorRecallReq request = new VectorRecallReq();
        request.setCollectionName(collectionName);
        request.setQuery(question);
        request.setLimit(resolveTopK(reactorConfig));
        request.setScoreThreshold(resolveScoreThreshold(reactorConfig));
        request.setTimeout(RECALL_TIMEOUT_MILLIS);
        request.setPayloads(List.of("text", "chunkIndex", "fileName", "sessionId"));
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("sessionId", sessionId);
        filters.put("fileName", fileName);
        request.setKeywordFilterMap(filters);
        return vectorService.vectorRecall(request);
    }

    private static String formatHits(String fileName, String question, List<Map<String, Object>> hits) {
        StringBuilder builder = new StringBuilder();
        builder.append("文件: ").append(fileName).append('\n');
        builder.append("问题: ").append(question).append('\n');
        builder.append("检索模式: session_file_rag\n");
        builder.append("相关片段:\n");
        int rank = 1;
        for (Map<String, Object> hit : hits) {
            String text = extractText(hit);
            if (StringUtils.isBlank(text)) {
                continue;
            }
            Object score = hit.get("score");
            builder.append(rank++)
                    .append(". ");
            if (score != null) {
                builder.append("(score=").append(score).append(") ");
            }
            builder.append(text.trim()).append("\n\n");
            if (builder.length() >= MAX_RESULT_CHARS) {
                builder.append("...[召回结果已截断]");
                break;
            }
        }
        if (rank == 1) {
            return "未从文件中召回与问题相关的片段: " + fileName;
        }
        return builder.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> hit) {
        if (hit == null) {
            return "";
        }
        Object direct = hit.get("text");
        if (direct != null && StringUtils.isNotBlank(String.valueOf(direct))) {
            return String.valueOf(direct);
        }
        Object payload = hit.get("payload");
        if (payload instanceof Map<?, ?> payloadMap) {
            Object text = ((Map<String, Object>) payloadMap).get("text");
            if (text != null) {
                return String.valueOf(text);
            }
        }
        Object payloads = hit.get("payloads");
        if (payloads instanceof Map<?, ?> payloadMap) {
            Object text = ((Map<String, Object>) payloadMap).get("text");
            if (text != null) {
                return String.valueOf(text);
            }
        }
        return "";
    }

    private static String stableChunkId(String sessionId, String fileName, int index, String chunkText) {
        String material = sessionId + "|" + fileName + "|" + index + "|" + Integer.toHexString(chunkText.hashCode());
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String resolveCollection(ReactorConfig reactorConfig) {
        if (reactorConfig != null && StringUtils.isNotBlank(reactorConfig.getSessionFileRagCollection())) {
            return reactorConfig.getSessionFileRagCollection().trim();
        }
        return "agent_session_file_chunks";
    }

    private static int resolveChunkChars(ReactorConfig reactorConfig) {
        if (reactorConfig != null && reactorConfig.getSessionFileRagChunkChars() != null) {
            return reactorConfig.getSessionFileRagChunkChars();
        }
        return DEFAULT_CHUNK_CHARS;
    }

    private static int resolveOverlapChars(ReactorConfig reactorConfig) {
        if (reactorConfig != null && reactorConfig.getSessionFileRagChunkOverlapChars() != null) {
            return reactorConfig.getSessionFileRagChunkOverlapChars();
        }
        return DEFAULT_OVERLAP_CHARS;
    }

    private static int resolveTopK(ReactorConfig reactorConfig) {
        if (reactorConfig != null && reactorConfig.getSessionFileRagTopK() != null) {
            return Math.max(1, reactorConfig.getSessionFileRagTopK());
        }
        return DEFAULT_TOP_K;
    }

    private static float resolveScoreThreshold(ReactorConfig reactorConfig) {
        if (reactorConfig != null && reactorConfig.getSessionFileRagScoreThreshold() != null) {
            return reactorConfig.getSessionFileRagScoreThreshold();
        }
        return DEFAULT_SCORE_THRESHOLD;
    }

    private static String truncateForFallback(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= AnalyzeFileTool.MAX_DIRECT_CHARS) {
            return content;
        }
        return content.substring(0, AnalyzeFileTool.MAX_DIRECT_CHARS)
                + "\n\n[内容过长，向量检索不可用，已截断]";
    }

    static String normalizeFileName(String fileName) {
        return StringUtils.defaultString(fileName).trim().toLowerCase(Locale.ROOT);
    }
}
