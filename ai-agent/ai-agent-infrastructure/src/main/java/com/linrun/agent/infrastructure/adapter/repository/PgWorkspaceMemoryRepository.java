package com.linrun.agent.infrastructure.adapter.repository;

import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryEntry;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryRepository;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemorySource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** PostgreSQL persistence for explicit Workspace Memory; it intentionally has no vector dependency. */
@Repository
@ConditionalOnBean(name = "pgJdbcTemplate")
public class PgWorkspaceMemoryRepository implements WorkspaceMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PgWorkspaceMemoryRepository(@Qualifier("pgJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkspaceMemoryEntry save(WorkspaceMemoryEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO workspace_memory (
                  memory_id, tenant_id, owner_id, topic, content, source, confidence, revision, created_at, updated_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, owner_id, topic) DO UPDATE SET
                  content = EXCLUDED.content,
                  source = EXCLUDED.source,
                  confidence = EXCLUDED.confidence,
                  revision = EXCLUDED.revision,
                  updated_at = EXCLUDED.updated_at,
                  expires_at = EXCLUDED.expires_at
                """, entry.id(), entry.tenantId(), entry.ownerId(), entry.topic(), entry.content(),
                entry.source().name(), entry.confidence(), entry.revision(),
                Timestamp.from(Instant.ofEpochMilli(entry.createdAtEpochMillis())), Timestamp.from(Instant.now()),
                entry.expiresAtEpochMillis() == null ? null : Timestamp.from(Instant.ofEpochMilli(entry.expiresAtEpochMillis())));
        return entry;
    }

    @Override
    public List<WorkspaceMemoryEntry> list(String tenantId, String ownerId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT memory_id, tenant_id, owner_id, topic, content, source, confidence, revision, created_at, expires_at
                FROM workspace_memory
                WHERE tenant_id = ? AND owner_id = ? AND (expires_at IS NULL OR expires_at > now())
                ORDER BY updated_at DESC
                LIMIT ?
                """, tenantId, ownerId, limit).stream().map(this::toEntry).toList();
    }

    @Override
    public boolean delete(String tenantId, String ownerId, String memoryId) {
        return jdbcTemplate.update("""
                DELETE FROM workspace_memory
                WHERE tenant_id = ? AND owner_id = ? AND memory_id = ?
                """, tenantId, ownerId, memoryId) == 1;
    }

    @Override
    public int clear(String tenantId, String ownerId) {
        return jdbcTemplate.update("DELETE FROM workspace_memory WHERE tenant_id = ? AND owner_id = ?", tenantId, ownerId);
    }

    private WorkspaceMemoryEntry toEntry(Map<String, Object> row) {
        Object source = row.get("source");
        WorkspaceMemorySource memorySource;
        try {
            memorySource = WorkspaceMemorySource.valueOf(String.valueOf(source));
        } catch (Exception ignored) {
            memorySource = WorkspaceMemorySource.EXPLICIT_USER;
        }
        return new WorkspaceMemoryEntry(
                String.valueOf(row.get("memory_id")), String.valueOf(row.get("tenant_id")),
                String.valueOf(row.get("owner_id")), String.valueOf(row.get("topic")),
                String.valueOf(row.get("content")), memorySource,
                ((Number) row.get("confidence")).doubleValue(), ((Number) row.get("revision")).longValue(),
                toEpoch(row.get("created_at")), row.get("expires_at") == null ? null : toEpoch(row.get("expires_at")));
    }

    private long toEpoch(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toEpochMilli();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().toEpochMilli();
        }
        return Instant.now().toEpochMilli();
    }
}
