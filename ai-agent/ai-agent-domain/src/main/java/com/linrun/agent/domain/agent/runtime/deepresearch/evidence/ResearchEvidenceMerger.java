package com.linrun.agent.domain.agent.runtime.deepresearch.evidence;

import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic P90 merge: final evidence only, content-hash dedupe and explicit conflicts. */
public final class ResearchEvidenceMerger {

    private static final long STALE_AFTER_DAYS = 365L * 5L;

    public MergeResult merge(List<ResearchEvidencePacket> input) {
        List<ResearchEvidencePacket> supplied = input == null ? List.of() : input;
        List<ResearchEvidencePacket> eligible = supplied.stream()
                .filter(ResearchEvidencePacket::isFinalReportEvidence)
                .toList();
        Map<String, ResearchEvidencePacket> unique = new LinkedHashMap<>();
        for (ResearchEvidencePacket packet : eligible) {
            String key = packet.contentHash() + "|" + canonicalUrl(packet.url());
            ResearchEvidencePacket existing = unique.get(key);
            if (existing == null || packet.fetchedAtEpochMillis() > existing.fetchedAtEpochMillis()) {
                unique.put(key, normalizedFreshness(packet));
            }
        }
        List<ResearchEvidencePacket> evidence = new ArrayList<>(unique.values());
        evidence.sort(Comparator.comparingLong(ResearchEvidencePacket::effectiveSourceTimeMillis).reversed()
                .thenComparing(ResearchEvidencePacket::evidenceId));

        Map<String, Set<String>> relations = new LinkedHashMap<>();
        for (ResearchEvidencePacket packet : evidence) {
            relations.computeIfAbsent(packet.claimId(), ignored -> new LinkedHashSet<>())
                    .add(StringUtils.upperCase(packet.relation(), Locale.ROOT));
        }
        List<String> conflicted = relations.entrySet().stream()
                .filter(entry -> entry.getValue().contains("SUPPORTS") && entry.getValue().contains("CONTRADICTS"))
                .map(Map.Entry::getKey)
                .toList();
        Map<String, String> claimStatus = new LinkedHashMap<>();
        relations.forEach((claimId, values) -> {
            boolean allStale = evidence.stream().filter(packet -> packet.claimId().equals(claimId))
                    .allMatch(packet -> "STALE".equals(packet.freshness()));
            claimStatus.put(claimId, conflicted.contains(claimId) ? "CONFLICTED" : allStale ? "OUTDATED" : "SUPPORTED");
        });
        int duplicates = Math.max(0, eligible.size() - evidence.size());
        Map<String, Object> metrics = Map.of(
                "candidateCount", supplied.size(),
                "verifiedSourceCount", evidence.size(),
                "duplicateRate", eligible.isEmpty() ? 0D : (double) duplicates / eligible.size(),
                "conflictRate", claimStatus.isEmpty() ? 0D : (double) conflicted.size() / claimStatus.size(),
                "staleSourceCount", evidence.stream().filter(packet -> "STALE".equals(packet.freshness())).count());
        return new MergeResult(List.copyOf(evidence), List.copyOf(conflicted), Map.copyOf(claimStatus), metrics);
    }

    private ResearchEvidencePacket normalizedFreshness(ResearchEvidencePacket packet) {
        if (StringUtils.isNotBlank(packet.freshness()) && !"UNKNOWN".equalsIgnoreCase(packet.freshness())) {
            return packet;
        }
        long sourceTime = packet.effectiveSourceTimeMillis();
        boolean stale = sourceTime > 0 && Instant.ofEpochMilli(sourceTime)
                .isBefore(Instant.now().minus(STALE_AFTER_DAYS, ChronoUnit.DAYS));
        return packet.withFreshness(stale ? "STALE" : "FRESH");
    }

    private String canonicalUrl(String value) {
        String url = StringUtils.substringBefore(StringUtils.trimToEmpty(value), "#");
        return StringUtils.lowerCase(url, Locale.ROOT);
    }

    public record MergeResult(List<ResearchEvidencePacket> evidence,
                              List<String> conflictedClaimIds,
                              Map<String, String> claimStatus,
                              Map<String, Object> metrics) {
    }
}
