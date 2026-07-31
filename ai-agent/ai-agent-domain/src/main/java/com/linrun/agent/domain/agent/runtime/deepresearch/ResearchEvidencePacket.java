package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

public record ResearchEvidencePacket(String claimId,
                                     String title,
                                     String url,
                                     String snippet,
                                     String evidenceId,
                                     String contentHash,
                                     long fetchedAtEpochMillis,
                                     long publishedAtEpochMillis,
                                     String sourceType,
                                     String reliability,
                                     String freshness,
                                     String retrievalTraceId,
                                     String claimStatement,
                                     String relation,
                                     int excerptStartOffset,
                                     int excerptEndOffset,
                                     boolean offlineFixture) {

    /** P40 compatibility constructor. New runtime evidence always uses extracted/fetched metadata. */
    public ResearchEvidencePacket(String claimId, String title, String url, String snippet) {
        this(claimId, title, url, snippet,
                stableId(claimId, url, snippet), sha256(url + "\n" + snippet), System.currentTimeMillis(), 0L,
                "FETCHED_PAGE", "UNASSESSED", "UNKNOWN", "legacy", claimId, "SUPPORTS", 0,
                StringUtils.length(snippet), false);
    }

    public static ResearchEvidencePacket candidate(String claimId, String title, String url, String snippet) {
        return new ResearchEvidencePacket(claimId, title, url, snippet, stableId(claimId, url, snippet), "", 0L, 0L,
                "SEARCH_CANDIDATE", "UNASSESSED", "UNKNOWN", "", claimId, "MENTIONS", 0,
                StringUtils.length(snippet), false);
    }

    /** Compatibility alias for older persisted graph state. */
    public String id() {
        return claimId;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("claimId", claimId);
        map.put("id", claimId);
        map.put("title", title);
        map.put("url", url);
        map.put("snippet", snippet);
        map.put("evidenceId", evidenceId);
        map.put("contentHash", contentHash);
        map.put("fetchedAtEpochMillis", fetchedAtEpochMillis);
        map.put("publishedAtEpochMillis", publishedAtEpochMillis);
        map.put("sourceType", sourceType);
        map.put("reliability", reliability);
        map.put("freshness", freshness);
        map.put("retrievalTraceId", retrievalTraceId);
        map.put("claimStatement", claimStatement);
        map.put("relation", relation);
        map.put("excerptStartOffset", excerptStartOffset);
        map.put("excerptEndOffset", excerptEndOffset);
        map.put("offlineFixture", offlineFixture);
        return map;
    }

    public static ResearchEvidencePacket from(Object value) {
        if (value instanceof ResearchEvidencePacket packet) {
            return packet;
        }
        if (value instanceof Map<?, ?> map) {
            return new ResearchEvidencePacket(
                    StringUtils.firstNonBlank(string(map.get("claimId")), string(map.get("id"))),
                    string(map.get("title")),
                    string(map.get("url")),
                    string(map.get("snippet")),
                    StringUtils.defaultIfBlank(string(map.get("evidenceId")), stableId(
                            StringUtils.firstNonBlank(string(map.get("claimId")), string(map.get("id"))),
                            string(map.get("url")), string(map.get("snippet")))),
                    string(map.get("contentHash")),
                    number(map.get("fetchedAtEpochMillis"), 0L),
                    number(map.get("publishedAtEpochMillis"), 0L),
                    StringUtils.defaultIfBlank(string(map.get("sourceType")), "SEARCH_CANDIDATE"),
                    StringUtils.defaultIfBlank(string(map.get("reliability")), "UNASSESSED"),
                    StringUtils.defaultIfBlank(string(map.get("freshness")), "UNKNOWN"),
                    string(map.get("retrievalTraceId")),
                    StringUtils.defaultIfBlank(string(map.get("claimStatement")),
                            StringUtils.firstNonBlank(string(map.get("claimId")), string(map.get("id")))),
                    StringUtils.defaultIfBlank(string(map.get("relation")), "SUPPORTS"),
                    integer(map.get("excerptStartOffset")), integer(map.get("excerptEndOffset"), StringUtils.length(string(map.get("snippet")))),
                    bool(map.get("offlineFixture"))
            );
        }
        return candidate("", "", "", "");
    }

    public boolean hasSource() {
        return StringUtils.isNoneBlank(claimId, title, url, snippet)
                && (StringUtils.startsWithIgnoreCase(url, "https://")
                || StringUtils.startsWithIgnoreCase(url, "http://"));
    }

    /** Search snippets/candidates are deliberately excluded from reports and the durable ledger. */
    public boolean isFinalReportEvidence() {
        return hasSource() && StringUtils.isNotBlank(contentHash) && fetchedAtEpochMillis > 0
                && ("FETCHED_PAGE".equalsIgnoreCase(sourceType) || "FILE_ARTIFACT".equalsIgnoreCase(sourceType));
    }

    public long effectiveSourceTimeMillis() {
        return publishedAtEpochMillis > 0 ? publishedAtEpochMillis : fetchedAtEpochMillis;
    }

    public ResearchEvidencePacket withFreshness(String value) {
        return new ResearchEvidencePacket(claimId, title, url, snippet, evidenceId, contentHash, fetchedAtEpochMillis,
                publishedAtEpochMillis, sourceType, reliability, value, retrievalTraceId, claimStatement, relation,
                excerptStartOffset, excerptEndOffset, offlineFixture);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(string(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int integer(Object value) {
        return (int) number(value, 0L);
    }

    private static int integer(Object value, int fallback) {
        long number = number(value, fallback);
        return number > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) number;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(string(value));
    }

    private static String stableId(String claimId, String url, String snippet) {
        return "evidence-" + sha256(StringUtils.defaultString(claimId) + "\n" + url + "\n" + snippet).substring(0, 20);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required for evidence provenance", error);
        }
    }
}
