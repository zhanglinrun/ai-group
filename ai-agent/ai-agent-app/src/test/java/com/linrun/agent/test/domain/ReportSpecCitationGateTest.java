package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchPlan;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.CitationGate;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class ReportSpecCitationGateTest {

    @Test
    public void shouldPassOnlyForHashBoundEvidenceFromCurrentRun() {
        ResearchEvidencePacket evidence = evidence("evidence-1", "claim-1", "SUPPORTS", "FRESH");
        ReportSpec spec = ReportSpec.fromResearch(ResearchPlan.create("citation gate"), List.of(evidence));

        CitationGate.Result result = new CitationGate().validate(spec, List.of(evidence));

        Assert.assertTrue(result.passed());
        Assert.assertEquals(1D, result.citationCoverage(), 0.001D);
        Assert.assertEquals("researchpilot-deterministic-v1", spec.rendererVersion());
    }

    @Test
    public void shouldFailClosedForTamperedCitationHashOrForeignEvidence() {
        ResearchEvidencePacket evidence = evidence("evidence-1", "claim-1", "SUPPORTS", "FRESH");
        ReportSpec original = ReportSpec.fromResearch(ResearchPlan.create("citation gate"), List.of(evidence));
        ReportSpec.Citation citation = original.citations().getFirst();
        ReportSpec tampered = new ReportSpec(original.title(), original.executiveSummary(), original.methodology(),
                original.sections(), original.claims(), List.of(new ReportSpec.Citation(citation.evidenceId(), citation.claimId(),
                citation.sourceUrl(), "bad-hash", citation.exactQuote(), citation.startOffset(), citation.endOffset(),
                citation.fetchedAtEpochMillis(), citation.offlineFixture())), original.conflicts(), original.limitations(),
                original.generatedAt(), original.rendererVersion());

        CitationGate.Result hashResult = new CitationGate().validate(tampered, List.of(evidence));
        CitationGate.Result runResult = new CitationGate().validate(original,
                List.of(evidence("foreign-evidence", "claim-1", "SUPPORTS", "FRESH")));

        Assert.assertFalse(hashResult.passed());
        Assert.assertTrue(hashResult.findings().stream().anyMatch(value -> value.contains("hash")));
        Assert.assertFalse(runResult.passed());
        Assert.assertTrue(runResult.findings().stream().anyMatch(value -> value.contains("current run")));
    }

    @Test
    public void shouldRequireExplicitUncertaintyForConflictedClaims() {
        ResearchEvidencePacket support = evidence("evidence-support", "claim-1", "SUPPORTS", "FRESH");
        ResearchEvidencePacket contradict = evidence("evidence-contradict", "claim-1", "CONTRADICTS", "FRESH");
        ReportSpec original = ReportSpec.fromResearch(ResearchPlan.create("conflict"), List.of(support, contradict));
        ReportSpec.Claim claim = original.claims().getFirst();
        ReportSpec unlabelled = new ReportSpec(original.title(), original.executiveSummary(), original.methodology(),
                original.sections(), List.of(new ReportSpec.Claim(claim.id(), claim.statement(), claim.evidenceIds(), "NONE")),
                original.citations(), original.conflicts(), original.limitations(), original.generatedAt(), original.rendererVersion());

        CitationGate.Result result = new CitationGate().validate(unlabelled, List.of(support, contradict));

        Assert.assertFalse(result.passed());
        Assert.assertTrue(result.findings().stream().anyMatch(value -> value.contains("uncertainty")));
    }

    private ResearchEvidencePacket evidence(String evidenceId, String claimId, String relation, String freshness) {
        return new ResearchEvidencePacket(claimId, "source", "https://source.example/" + evidenceId,
                "verified quote " + evidenceId, evidenceId, "sha256-" + evidenceId, 1000L, 0L,
                "FETCHED_PAGE", "HIGH", freshness, "trace-" + evidenceId, "verified claim", relation,
                0, ("verified quote " + evidenceId).length(), false);
    }
}
