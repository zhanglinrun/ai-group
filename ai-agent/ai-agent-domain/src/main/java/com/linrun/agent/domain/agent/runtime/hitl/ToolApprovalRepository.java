package com.linrun.agent.domain.agent.runtime.hitl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class ToolApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolApprovalRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ToolApproval create(ToolApproval approval) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO agent_tool_approval " +
                            "(owner_id, run_id, tool_call_id, tool_name, arguments_preview, " +
                            "estimated_microcredits, status, expires_at, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, approval.getOwnerId());
            statement.setString(2, approval.getRunId());
            statement.setString(3, approval.getToolCallId());
            statement.setString(4, approval.getToolName());
            statement.setString(5, approval.getArgumentsPreview());
            statement.setLong(6, approval.getEstimatedMicrocredits());
            statement.setTimestamp(7, Timestamp.from(approval.getExpiresAt()));
            statement.setTimestamp(8, Timestamp.from(approval.getCreatedAt()));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("tool approval insert returned no id");
        }
        return approval.toBuilder().id(id.longValue()).build();
    }

    public ToolApproval findById(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM agent_tool_approval WHERE id = ?", id);
        return rows.isEmpty() ? null : map(rows.get(0));
    }

    public List<ToolApproval> findPending(String ownerId, String runId) {
        return jdbcTemplate.queryForList(
                        "SELECT * FROM agent_tool_approval " +
                                "WHERE owner_id = ? AND run_id = ? AND status = 'PENDING' AND expires_at > NOW(3) " +
                                "ORDER BY id", ownerId, runId)
                .stream().map(this::map).toList();
    }

    public boolean decide(long id, String ownerId, ApprovalDecision decision, String decisionPayload) {
        int affected = jdbcTemplate.update(
                "UPDATE agent_tool_approval SET status = ?, decision_payload = ?, decided_at = NOW(3) " +
                        "WHERE id = ? AND owner_id = ? AND status = 'PENDING' AND expires_at > NOW(3)",
                decision.name(), decisionPayload, id, ownerId);
        return affected == 1;
    }

    public boolean timeout(long id) {
        return jdbcTemplate.update(
                "UPDATE agent_tool_approval SET status = 'TIMEOUT', decided_at = NOW(3) " +
                        "WHERE id = ? AND status = 'PENDING'", id) == 1;
    }

    private ToolApproval map(Map<String, Object> row) {
        return ToolApproval.builder()
                .id(longValue(row.get("id")))
                .ownerId(stringValue(row.get("owner_id")))
                .runId(stringValue(row.get("run_id")))
                .toolCallId(stringValue(row.get("tool_call_id")))
                .toolName(stringValue(row.get("tool_name")))
                .argumentsPreview(stringValue(row.get("arguments_preview")))
                .estimatedMicrocredits(longValue(row.get("estimated_microcredits")))
                .status(ApprovalDecision.valueOf(stringValue(row.get("status"))))
                .expiresAt(instantValue(row.get("expires_at")))
                .decisionPayload(stringValue(row.get("decision_payload")))
                .createdAt(instantValue(row.get("created_at")))
                .decidedAt(instantValue(row.get("decided_at")))
                .build();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private Instant instantValue(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
    }
}
