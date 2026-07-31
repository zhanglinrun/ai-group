package com.linrun.agent.domain.agent.runtime.deepresearch.evidence;

import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

public class ResearchEvidenceMergerTest {

    private final ResearchEvidenceMerger merger = new ResearchEvidenceMerger();

    @Test
    public void shouldRejectSearchCandidatesAndDeduplicateFetchedContent() {
        ResearchEvidencePacket candidate = ResearchEvidencePacket.candidate("claim-1", "candidate", "https://candidate.example", "snippet");
        ResearchEvidencePacket first = verified("claim-1", "evidence-a", "https://source.example/report#section", "same-hash", "SUPPORTS", 10L, false);
        ResearchEvidencePacket duplicate = verified("claim-1", "evidence-b", "https://source.example/report", "same-hash", "SUPPORTS", 20L, false);

        ResearchEvidenceMerger.MergeResult result = merger.merge(List.of(candidate, first, duplicate));

        Assert.assertEquals(1, result.evidence().size());
        Assert.assertEquals("evidence-a", result.evidence().getFirst().evidenceId());
        Assert.assertEquals(0.5D, (Double) result.metrics().get("duplicateRate"), 0D);
    }

    @Test
    public void shouldKeepContradictorySourcesAndMarkClaimConflictedAndStale() {
        long stale = Instant.now().minus(6L * 365L, ChronoUnit.DAYS).toEpochMilli();
        ResearchEvidencePacket support = verified("claim-conflict", "evidence-support", "https://a.example", "hash-a", "SUPPORTS", stale, false);
        ResearchEvidencePacket contradiction = verified("claim-conflict", "evidence-contradict", "https://b.example", "hash-b", "CONTRADICTS", stale, true);

        ResearchEvidenceMerger.MergeResult result = merger.merge(List.of(support, contradiction));

        Assert.assertEquals(2, result.evidence().size());
        Assert.assertEquals(List.of("claim-conflict"), result.conflictedClaimIds());
        Assert.assertEquals("CONFLICTED", result.claimStatus().get("claim-conflict"));
        Assert.assertEquals(2L, result.metrics().get("staleSourceCount"));
        Assert.assertTrue(result.evidence().stream().anyMatch(ResearchEvidencePacket::offlineFixture));
    }

    @Test
    public void shouldNotUpgradePreP90CheckpointSnippetWithoutFetchProvenance() {
        ResearchEvidencePacket legacyCheckpoint = ResearchEvidencePacket.from(Map.of(
                "claimId", "legacy-claim", "title", "legacy", "url", "https://legacy.example", "snippet", "search snippet"));

        ResearchEvidenceMerger.MergeResult result = merger.merge(List.of(legacyCheckpoint));

        Assert.assertFalse(legacyCheckpoint.isFinalReportEvidence());
        Assert.assertTrue(result.evidence().isEmpty());
    }

    private ResearchEvidencePacket verified(String claimId, String evidenceId, String url, String hash,
                                            String relation, long publishedAt, boolean offlineFixture) {
        return new ResearchEvidencePacket(claimId, "source", url, "verbatim quote", evidenceId, hash,
                System.currentTimeMillis(), publishedAt, "FETCHED_PAGE", "HIGH", "UNKNOWN", "trace-1",
                "claim statement", relation, 0, 14, offlineFixture);
    }
}
