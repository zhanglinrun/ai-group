package com.linrun.agent.domain.agent.runtime.deepresearch.report;

import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail-closed final report gate.  The supplied evidence list is this run's allowlist. */
public final class CitationGate {

    public Result validate(ReportSpec spec, List<ResearchEvidencePacket> currentRunEvidence) {
        List<String> findings = new ArrayList<>();
        if (spec == null) {
            return new Result(false, List.of("report spec is missing"), 0D);
        }
        Map<String, ResearchEvidencePacket> evidenceById = new LinkedHashMap<>();
        for (ResearchEvidencePacket packet : currentRunEvidence == null ? List.<ResearchEvidencePacket>of() : currentRunEvidence) {
            if (packet.isFinalReportEvidence()) {
                evidenceById.putIfAbsent(packet.evidenceId(), packet);
            }
        }
        Map<String, List<ReportSpec.Citation>> citationsByClaim = new LinkedHashMap<>();
        for (ReportSpec.Citation citation : spec.citations()) {
            ResearchEvidencePacket packet = evidenceById.get(citation.evidenceId());
            if (packet == null) {
                findings.add("citation is not evidence from the current run=" + citation.evidenceId());
                continue;
            }
            if (!StringUtils.equals(citation.claimId(), packet.claimId())) {
                findings.add("citation claim does not match evidence=" + citation.evidenceId());
            }
            if (!StringUtils.equals(citation.sourceUrl(), packet.url())) {
                findings.add("citation URL does not match evidence=" + citation.evidenceId());
            }
            if (!StringUtils.equals(citation.contentHash(), packet.contentHash())) {
                findings.add("citation hash does not match evidence=" + citation.evidenceId());
            }
            if (!StringUtils.equals(citation.exactQuote(), packet.snippet())
                    || citation.startOffset() != packet.excerptStartOffset()
                    || citation.endOffset() != packet.excerptEndOffset()) {
                findings.add("citation quote or offsets do not match extracted evidence=" + citation.evidenceId());
            }
            citationsByClaim.computeIfAbsent(citation.claimId(), ignored -> new ArrayList<>()).add(citation);
        }

        Map<String, Set<String>> relations = new LinkedHashMap<>();
        Map<String, Boolean> allStale = new LinkedHashMap<>();
        for (ResearchEvidencePacket packet : evidenceById.values()) {
            relations.computeIfAbsent(packet.claimId(), ignored -> new LinkedHashSet<>())
                    .add(StringUtils.upperCase(packet.relation()));
            allStale.merge(packet.claimId(), "STALE".equalsIgnoreCase(packet.freshness()), Boolean::logicalAnd);
        }
        int claimsWithEvidence = 0;
        for (ReportSpec.Claim claim : spec.claims()) {
            List<ReportSpec.Citation> citations = citationsByClaim.getOrDefault(claim.id(), List.of());
            if (citations.isEmpty()) {
                findings.add("claim has no citation=" + claim.id());
            } else {
                claimsWithEvidence++;
            }
            Set<String> claimRelations = relations.getOrDefault(claim.id(), Set.of());
            boolean conflicted = claimRelations.contains("SUPPORTS") && claimRelations.contains("CONTRADICTS");
            boolean stale = Boolean.TRUE.equals(allStale.get(claim.id()));
            if (conflicted && !"CONFLICTED".equals(claim.uncertainty())) {
                findings.add("conflicted claim is missing uncertainty label=" + claim.id());
            }
            if (stale && !"OUTDATED".equals(claim.uncertainty())) {
                findings.add("outdated claim is missing uncertainty label=" + claim.id());
            }
        }
        double coverage = spec.claims().isEmpty() ? 1D : (double) claimsWithEvidence / spec.claims().size();
        return new Result(findings.isEmpty(), List.copyOf(findings), coverage);
    }

    public record Result(boolean passed, List<String> findings, double citationCoverage) {
        public Map<String, Object> toMap() {
            return Map.of("passed", passed, "findings", findings, "citationCoverage", citationCoverage,
                    "version", "citation-gate-v1");
        }
    }
}
