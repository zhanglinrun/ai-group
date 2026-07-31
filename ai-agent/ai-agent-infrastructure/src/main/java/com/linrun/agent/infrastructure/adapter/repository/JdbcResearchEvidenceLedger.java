package com.linrun.agent.infrastructure.adapter.repository;

import com.linrun.agent.domain.agent.runtime.deepresearch.evidence.ResearchEvidenceLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/** MySQL fact store for P90 evidence, claims, and their auditable edges. */
@Slf4j
@Repository
@ConditionalOnBean(name = "mysqlJdbcTemplate")
public class JdbcResearchEvidenceLedger implements ResearchEvidenceLedger {

    private final JdbcTemplate jdbc;

    public JdbcResearchEvidenceLedger(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void persist(Batch batch) {
        try {
            persistBatch(batch);
        } catch (DataAccessException persistenceFailure) {
            Throwable cause = persistenceFailure.getMostSpecificCause();
            log.error("P90 evidence ledger write failed runId={} evidenceCount={} claimCount={} edgeCount={} databaseError={}",
                    batch.runId(), batch.evidence().size(), batch.claims().size(), batch.edges().size(),
                    cause == null ? persistenceFailure.getClass().getSimpleName() : cause.getMessage());
            throw persistenceFailure;
        }
    }

    private void persistBatch(Batch batch) {
        for (Evidence evidence : batch.evidence()) {
            jdbc.update("""
                    INSERT INTO evidence_record (
                      tenant_id, owner_id, run_id, evidence_id, source_url, canonical_url, title, publisher,
                      published_at, fetched_at, content_hash, excerpt, source_type, reliability, freshness,
                      retrieval_trace_id, offline_fixture, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE source_url=VALUES(source_url), title=VALUES(title), publisher=VALUES(publisher),
                      published_at=VALUES(published_at), fetched_at=VALUES(fetched_at), excerpt=VALUES(excerpt),
                      source_type=VALUES(source_type), reliability=VALUES(reliability), freshness=VALUES(freshness),
                      retrieval_trace_id=VALUES(retrieval_trace_id), offline_fixture=VALUES(offline_fixture), updated_at=NOW(6)
                    """, batch.tenantId(), batch.ownerId(), batch.runId(), evidence.evidenceId(), evidence.sourceUrl(),
                    evidence.canonicalUrl(), evidence.title(), evidence.publisher(), timestamp(evidence.publishedAtEpochMillis()),
                    timestamp(evidence.fetchedAtEpochMillis()), evidence.contentHash(), evidence.excerpt(), evidence.sourceType(),
                    evidence.reliability(), evidence.freshness(), evidence.retrievalTraceId(), evidence.offlineFixture() ? 1 : 0);
        }
        for (Claim claim : batch.claims()) {
            jdbc.update("""
                    INSERT INTO claim_record (tenant_id, owner_id, run_id, claim_id, statement, claim_type, confidence, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE statement=VALUES(statement), claim_type=VALUES(claim_type), confidence=VALUES(confidence),
                      status=VALUES(status), updated_at=NOW(6)
                    """, batch.tenantId(), batch.ownerId(), batch.runId(), claim.claimId(), claim.statement(), claim.claimType(),
                    claim.confidence(), claim.status());
        }
        for (Edge edge : batch.edges()) {
            jdbc.update("""
                    INSERT INTO claim_evidence_edge (
                      tenant_id, owner_id, run_id, claim_id, evidence_id, relation, exact_quote,
                      excerpt_start_offset, excerpt_end_offset, extractor_version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE relation=VALUES(relation), exact_quote=VALUES(exact_quote),
                      excerpt_start_offset=VALUES(excerpt_start_offset), excerpt_end_offset=VALUES(excerpt_end_offset),
                      extractor_version=VALUES(extractor_version), updated_at=NOW(6)
                    """, batch.tenantId(), batch.ownerId(), batch.runId(), edge.claimId(), edge.evidenceId(), edge.relation(),
                    edge.exactQuote(), edge.startOffset(), edge.endOffset(), edge.extractorVersion());
        }
    }

    private Timestamp timestamp(long epochMillis) {
        return epochMillis > 0 ? new Timestamp(epochMillis) : null;
    }
}
