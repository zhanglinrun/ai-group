package com.linrun.agent.infrastructure.adapter.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshot;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotKey;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL fact-store implementation for recoverable P70 context snapshots. */
@Repository
@ConditionalOnBean(name = "mysqlJdbcTemplate")
public class JdbcContextSnapshotRepository implements ContextSnapshotRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public JdbcContextSnapshotRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate) {
        this.jdbcTemplate = mysqlJdbcTemplate;
    }

    @Override
    public Optional<ContextSnapshot> find(ContextSnapshotKey key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT snapshot_json
                FROM context_snapshot
                WHERE tenant_id = ? AND owner_id = ? AND session_id = ? AND run_id = ?
                """, key.tenantId(), key.ownerId(), key.sessionId(), key.runId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(String.valueOf(rows.getFirst().get("snapshot_json")), ContextSnapshot.class));
        } catch (Exception exception) {
            throw new IllegalStateException("stored context snapshot cannot be decoded", exception);
        }
    }

    @Override
    public Optional<ContextSnapshot> compareAndSet(ContextSnapshot next, long expectedRevision) {
        try {
            String json = JSON.writeValueAsString(next);
            if (expectedRevision == 0) {
                int inserted = jdbcTemplate.update("""
                        INSERT INTO context_snapshot (
                          tenant_id, owner_id, session_id, run_id, revision, snapshot_json, snapshot_hash,
                          summary_model, summary_version, source_hash, summary_degraded, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FROM_UNIXTIME(? / 1000), FROM_UNIXTIME(? / 1000))
                        """, next.key().tenantId(), next.key().ownerId(), next.key().sessionId(), next.key().runId(),
                        next.revision(), json, next.snapshotHash(), next.summaryModel(), next.summaryVersion(),
                        next.sourceHash(), next.summaryDegraded() ? 1 : 0,
                        next.createdAtEpochMillis(), next.updatedAtEpochMillis());
                return inserted == 1 ? Optional.of(next) : Optional.empty();
            }
            int updated = jdbcTemplate.update("""
                    UPDATE context_snapshot
                    SET revision = ?, snapshot_json = ?, snapshot_hash = ?, summary_model = ?, summary_version = ?,
                        source_hash = ?, summary_degraded = ?, updated_at = FROM_UNIXTIME(? / 1000)
                    WHERE tenant_id = ? AND owner_id = ? AND session_id = ? AND run_id = ? AND revision = ?
                    """, next.revision(), json, next.snapshotHash(), next.summaryModel(), next.summaryVersion(),
                    next.sourceHash(), next.summaryDegraded() ? 1 : 0, next.updatedAtEpochMillis(),
                    next.key().tenantId(), next.key().ownerId(), next.key().sessionId(), next.key().runId(),
                    expectedRevision);
            return updated == 1 ? Optional.of(next) : Optional.empty();
        } catch (DuplicateKeyException duplicate) {
            return Optional.empty();
        } catch (Exception exception) {
            throw new IllegalStateException("context snapshot compare-and-set failed", exception);
        }
    }
}
