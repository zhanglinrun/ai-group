package com.linrun.agent.domain.agent.runtime.tool.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ExtractedEvidenceToolOutput;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic evidence extraction from already fetched text.
 * It never invents a source or calls a hidden model; the output retains the
 * supplied source identity and exact excerpts for later report grounding.
 */
public class ExtractEvidenceTool implements BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_EXCERPTS = 5;
    private static final int MAX_EXCERPT_CHARS = 600;

    @Override
    public String getName() {
        return "extract_evidence";
    }

    @Override
    public String getDescription() {
        return "从已抓取的原文中提取可追溯证据片段，不生成新的事实。";
    }

    @Override
    public Map<String, Object> toParams() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "source_id", Map.of("type", "string"),
                        "source_url", Map.of("type", "string"),
                        "title", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "claim", Map.of("type", "string"),
                        "content_hash", Map.of("type", "string"),
                        "fetched_at_epoch_millis", Map.of("type", "integer"),
                        "source_type", Map.of("type", "string", "enum", List.of("FETCHED_PAGE", "FILE_ARTIFACT")),
                        "retrieval_trace_id", Map.of("type", "string"),
                        "offline_fixture", Map.of("type", "boolean")),
                "required", List.of("source_id", "content"),
                "additionalProperties", false);
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof Map<?, ?> raw)) {
            return failure("extract_evidence input must be an object");
        }
        String sourceId = text(raw.get("source_id"));
        String content = text(raw.get("content"));
        if (sourceId.isBlank() || content.isBlank()) {
            return failure("source_id and content are required");
        }
        String claim = text(raw.get("claim"));
        String suppliedHash = text(raw.get("content_hash"));
        long fetchedAt = number(raw.get("fetched_at_epoch_millis"));
        String sourceType = text(raw.get("source_type"));
        boolean fetchedSource = StringUtils.isNotBlank(suppliedHash) && fetchedAt > 0
                && ("FETCHED_PAGE".equals(sourceType) || "FILE_ARTIFACT".equals(sourceType));
        if (StringUtils.isNotBlank(suppliedHash) && !suppliedHash.equalsIgnoreCase(sha256(content))) {
            return failure("content_hash does not match fetched content");
        }
        if (fetchedSource && (text(raw.get("source_url")).isBlank() || claim.isBlank())) {
            return failure("fetched evidence requires source_url and claim");
        }
        List<ExtractedEvidenceToolOutput.Excerpt> excerpts = selectExcerpts(content, claim);
        ExtractedEvidenceToolOutput evidence = new ExtractedEvidenceToolOutput(
                sourceId, text(raw.get("source_url")), text(raw.get("title")), claim,
                StringUtils.defaultIfBlank(suppliedHash, sha256(content)), fetchedAt, sourceType,
                text(raw.get("retrieval_trace_id")), "p90-extract-v1", bool(raw.get("offline_fixture")),
                fetchedSource, excerpts);
        try {
            String serialized = MAPPER.writeValueAsString(evidence);
            return ToolResultPayload.structured(serialized, serialized, evidence);
        } catch (Exception error) {
            return failure("evidence serialization failed");
        }
    }

    @Override
    public boolean isConcurrencySafe(Object input) {
        return true;
    }

    private List<ExtractedEvidenceToolOutput.Excerpt> selectExcerpts(String content, String claim) {
        String[] sentences = content.split("(?<=[.!?。！？])\\s+");
        List<ExtractedEvidenceToolOutput.Excerpt> selected = new ArrayList<>();
        String normalizedClaim = claim.toLowerCase(Locale.ROOT);
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (normalizedClaim.isBlank()
                    || containsClaimToken(trimmed.toLowerCase(Locale.ROOT), normalizedClaim)) {
                String quote = trimmed.substring(0, Math.min(MAX_EXCERPT_CHARS, trimmed.length()));
                int start = content.indexOf(quote);
                selected.add(new ExtractedEvidenceToolOutput.Excerpt(quote, Math.max(0, start),
                        Math.max(0, start) + quote.length()));
            }
            if (selected.size() == MAX_EXCERPTS) {
                break;
            }
        }
        if (selected.isEmpty()) {
            String quote = content.substring(0, Math.min(MAX_EXCERPT_CHARS, content.length())).trim();
            selected.add(new ExtractedEvidenceToolOutput.Excerpt(quote, 0, quote.length()));
        }
        return List.copyOf(selected);
    }

    private boolean containsClaimToken(String sentence, String claim) {
        for (String token : claim.split("\\s+")) {
            if (token.length() >= 2 && sentence.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(text(value));
    }

    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required for evidence extraction", error);
        }
    }

    private ToolResultPayload failure(String message) {
        return ToolResultPayload.failure(message, message, null, message);
    }
}
