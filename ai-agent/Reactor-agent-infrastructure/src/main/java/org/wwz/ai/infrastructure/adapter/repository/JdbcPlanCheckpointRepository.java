package org.wwz.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointPhase;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointRepository;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointState;
import org.wwz.ai.domain.agent.checkpoint.PlanExecutionCheckpoint;
import org.wwz.ai.domain.agent.checkpoint.PlanResumeDecision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * MySQL/H2 compatible checkpoint repository.
 *
 * <p>Snapshot hash is verified on every read so a corrupted or manually edited checkpoint can never be resumed.</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcPlanCheckpointRepository implements PlanCheckpointRepository {

    private static final String SELECT_COLUMNS = """
            id, checkpoint_id, run_id, request_id, session_id, owner_id,
            sequence_no, phase, step_index, snapshot_json, snapshot_hash,
            resumable, resumed_by_request_id, resume_decision, resumed_at,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PlanExecutionCheckpoint save(PlanExecutionCheckpoint checkpoint) {
        validate(checkpoint);
        String snapshotJson = serialize(checkpoint.getState());
        String snapshotHash = sha256(snapshotJson);
        jdbcTemplate.update("""
                        INSERT INTO agent_run_checkpoint (
                            checkpoint_id, run_id, request_id, session_id, owner_id,
                            sequence_no, phase, step_index, snapshot_json, snapshot_hash, resumable
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                checkpoint.getCheckpointId(),
                checkpoint.getRunId(),
                checkpoint.getRequestId(),
                checkpoint.getSessionId(),
                checkpoint.getOwnerId(),
                checkpoint.getSequenceNo(),
                checkpoint.getPhase().name(),
                checkpoint.getStepIndex(),
                snapshotJson,
                snapshotHash,
                Boolean.TRUE.equals(checkpoint.getResumable()) ? 1 : 0);
        return findOwned(checkpoint.getCheckpointId(), checkpoint.getOwnerId(), checkpoint.getSessionId())
                .orElseThrow(() -> new IllegalStateException("Saved checkpoint cannot be read back"));
    }

    @Override
    public Optional<PlanExecutionCheckpoint> findOwned(String checkpointId, String ownerId, String sessionId) {
        List<PlanExecutionCheckpoint> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM agent_run_checkpoint "
                        + "WHERE checkpoint_id = ? AND owner_id = ? AND session_id = ? AND deleted = 0",
                checkpointRowMapper(), checkpointId, ownerId, sessionId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean claimForResume(String checkpointId,
                                  String ownerId,
                                  String sessionId,
                                  String resumedByRequestId,
                                  PlanResumeDecision decision) {
        int updated = jdbcTemplate.update("""
                        UPDATE agent_run_checkpoint
                        SET resumed_by_request_id = ?, resume_decision = ?,
                            resumed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                        WHERE checkpoint_id = ? AND owner_id = ? AND session_id = ?
                          AND resumable = 1 AND deleted = 0
                          AND (resumed_by_request_id IS NULL OR resumed_by_request_id = ?)
                        """,
                resumedByRequestId,
                decision == null ? PlanResumeDecision.SAFE_ONLY.name() : decision.name(),
                checkpointId,
                ownerId,
                sessionId,
                resumedByRequestId);
        return updated == 1;
    }

    @Override
    public void markRunCompleted(Long runId) {
        if (runId == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE agent_run_checkpoint
                SET resumable = 0, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND deleted = 0
                """, runId);
    }

    private RowMapper<PlanExecutionCheckpoint> checkpointRowMapper() {
        return (resultSet, rowNum) -> {
            String snapshotJson = resultSet.getString("snapshot_json");
            String expectedHash = resultSet.getString("snapshot_hash");
            String actualHash = sha256(snapshotJson);
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new IllegalStateException("Checkpoint snapshot hash mismatch: "
                        + resultSet.getString("checkpoint_id"));
            }
            return PlanExecutionCheckpoint.builder()
                    .id(resultSet.getLong("id"))
                    .checkpointId(resultSet.getString("checkpoint_id"))
                    .runId(resultSet.getLong("run_id"))
                    .requestId(resultSet.getString("request_id"))
                    .sessionId(resultSet.getString("session_id"))
                    .ownerId(resultSet.getString("owner_id"))
                    .sequenceNo(resultSet.getInt("sequence_no"))
                    .phase(PlanCheckpointPhase.valueOf(resultSet.getString("phase")))
                    .stepIndex(nullableInteger(resultSet, "step_index"))
                    .state(deserialize(snapshotJson))
                    .snapshotHash(expectedHash)
                    .resumable(resultSet.getBoolean("resumable"))
                    .resumedByRequestId(resultSet.getString("resumed_by_request_id"))
                    .resumeDecision(nullableDecision(resultSet.getString("resume_decision")))
                    .resumedAt(nullableDateTime(resultSet.getTimestamp("resumed_at")))
                    .createdAt(nullableDateTime(resultSet.getTimestamp("created_at")))
                    .updatedAt(nullableDateTime(resultSet.getTimestamp("updated_at")))
                    .build();
        };
    }

    private void validate(PlanExecutionCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getState() == null || checkpoint.getPhase() == null) {
            throw new IllegalArgumentException("Checkpoint, phase and state are required");
        }
        if (checkpoint.getCheckpointId() == null || checkpoint.getRunId() == null
                || checkpoint.getRequestId() == null || checkpoint.getSessionId() == null
                || checkpoint.getOwnerId() == null || checkpoint.getSequenceNo() == null) {
            throw new IllegalArgumentException("Checkpoint identity is incomplete");
        }
    }

    private String serialize(PlanCheckpointState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize plan checkpoint", exception);
        }
    }

    private PlanCheckpointState deserialize(String json) {
        try {
            return objectMapper.readValue(json, PlanCheckpointState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot deserialize plan checkpoint", exception);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime nullableDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private PlanResumeDecision nullableDecision(String value) {
        return value == null || value.isBlank() ? null : PlanResumeDecision.valueOf(value);
    }
}
