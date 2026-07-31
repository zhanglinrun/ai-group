package com.linrun.agent.domain.agent.runtime.deepresearch.evidence;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;

import java.util.ArrayList;
import java.util.List;

/** Durable P90 audit boundary. Implementations must fail closed on write failure. */
public interface ResearchEvidenceLedger {

    void persist(Batch batch);

    record Batch(String tenantId,
                 long ownerId,
                 String runId,
                 List<Evidence> evidence,
                 List<Claim> claims,
                 List<Edge> edges) {
        public Batch {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            claims = claims == null ? List.of() : List.copyOf(claims);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }

        public static Batch from(AgentContext context, String runId, ResearchEvidenceMerger.MergeResult merged) {
            String tenant = context == null || context.getTenantId() == null || context.getTenantId().isBlank()
                    ? "default" : context.getTenantId();
            long owner = context == null || context.getOwnerId() == null ? 0L : context.getOwnerId();
            List<Evidence> evidence = new ArrayList<>();
            List<Claim> claims = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            for (ResearchEvidencePacket packet : merged.evidence()) {
                evidence.add(Evidence.from(packet));
                String status = merged.claimStatus().getOrDefault(packet.claimId(), "UNSUPPORTED");
                if (claims.stream().noneMatch(claim -> claim.claimId().equals(packet.claimId()))) {
                    claims.add(new Claim(packet.claimId(), packet.claimStatement(), "FACTUAL", 1D, status));
                }
                edges.add(new Edge(packet.claimId(), packet.evidenceId(), packet.relation(), packet.snippet(),
                        packet.excerptStartOffset(), packet.excerptEndOffset(), "p90-extract-v1"));
            }
            return new Batch(tenant, owner, runId, evidence, claims, edges);
        }
    }

    record Evidence(String evidenceId, String sourceUrl, String canonicalUrl, String title, String publisher,
                    long publishedAtEpochMillis, long fetchedAtEpochMillis, String contentHash, String excerpt,
                    String sourceType, String reliability, String freshness, String retrievalTraceId,
                    boolean offlineFixture) {
        static Evidence from(ResearchEvidencePacket packet) {
            return new Evidence(packet.evidenceId(), packet.url(), packet.url(), packet.title(), "",
                    packet.publishedAtEpochMillis(), packet.fetchedAtEpochMillis(), packet.contentHash(), packet.snippet(),
                    packet.sourceType(), packet.reliability(), packet.freshness(), packet.retrievalTraceId(), packet.offlineFixture());
        }
    }

    record Claim(String claimId, String statement, String claimType, double confidence, String status) {
    }

    record Edge(String claimId, String evidenceId, String relation, String exactQuote,
                int startOffset, int endOffset, String extractorVersion) {
    }
}
