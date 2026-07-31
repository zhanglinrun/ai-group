package com.linrun.agent.infrastructure.adapter.repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolAttempt;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolCallbackResult;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolExecutionMode;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolExecutionRequest;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolInvocation;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolOutboxMessage;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolOutboxStatus;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolScheduleResult;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolStatus;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolStore;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolWorkerCallback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** MySQL persistence for P50 durable-tool control state and outbox fallback scanning. */
@Repository
public class JdbcDurableToolStore implements DurableToolStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDurableToolStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public DurableToolScheduleResult schedule(DurableToolExecutionRequest request, Instant now) {
        // Serializing admissions on the owning run prevents concurrent same-operation side effects.
        jdbcTemplate.query("SELECT id FROM dialogue_run WHERE id = ? FOR UPDATE", rs -> null, request.getRunId());
        DurableToolInvocation source = findExecutedOperation(request.getRunId(), request.getOperationKey(),
                request.getToolInvocationId()).orElse(null);
        if (source != null) {
            jdbcTemplate.update("""
                    UPDATE tool_invocation
                    SET operation_key = ?, execution_mode = 'REUSED', source_invocation_id = ?,
                        durable_status = ?, durable_fencing_token = ?, tool_result = ?, llm_observation = ?,
                        status = ?, error_msg = ?, update_time = NOW()
                    WHERE id = ? AND run_id = ? AND deleted = 0
                    """, request.getOperationKey(), source.getToolInvocationId(), source.getStatus().name(),
                    request.getFencingToken(), source.getResultPayload(), source.getResultPayload(),
                    ledgerStatus(source.getStatus()), source.getErrorType(), request.getToolInvocationId(), request.getRunId());
            DurableToolInvocation reused = DurableToolInvocation.builder()
                    .toolInvocationId(request.getToolInvocationId())
                    .runId(request.getRunId())
                    .requestId(request.getRequestId())
                    .toolCallId(request.getToolCallId())
                    .toolName(request.getToolName())
                    .operationKey(request.getOperationKey())
                    .executionMode(DurableToolExecutionMode.REUSED)
                    .sourceInvocationId(source.getToolInvocationId())
                    .status(source.getStatus())
                    .fencingToken(request.getFencingToken())
                    .retryable(false)
                    .resultPayload(source.getResultPayload())
                    .resultHash(source.getResultHash())
                    .errorType(source.getErrorType())
                    .heartbeatAt(now)
                    .build();
            return DurableToolScheduleResult.builder().invocation(reused).reused(true).build();
        }

        int updated = jdbcTemplate.update("""
                UPDATE tool_invocation
                SET operation_key = ?, execution_mode = 'EXECUTED', source_invocation_id = NULL,
                    durable_status = 'SCHEDULED', durable_fencing_token = ?, durable_lease_expires_at = NULL,
                    update_time = NOW()
                WHERE id = ? AND run_id = ? AND deleted = 0
                """, request.getOperationKey(), request.getFencingToken(), request.getToolInvocationId(), request.getRunId());
        if (updated != 1) {
            throw new IllegalStateException("durable tool ledger row is missing: " + request.getToolInvocationId());
        }
        jdbcTemplate.update("""
                INSERT INTO tool_outbox (tool_invocation_id, operation_key, status, retry_count, next_attempt_at,
                                         create_time, update_time, deleted)
                VALUES (?, ?, 'SCHEDULED', 0, ?, NOW(), NOW(), 0)
                """, request.getToolInvocationId(), request.getOperationKey(), timestamp(now));
        DurableToolInvocation invocation = DurableToolInvocation.builder()
                .toolInvocationId(request.getToolInvocationId())
                .runId(request.getRunId())
                .requestId(request.getRequestId())
                .toolCallId(request.getToolCallId())
                .toolName(request.getToolName())
                .operationKey(request.getOperationKey())
                .executionMode(DurableToolExecutionMode.EXECUTED)
                .status(DurableToolStatus.SCHEDULED)
                .fencingToken(request.getFencingToken())
                .retryable(request.isRetryable())
                .heartbeatAt(now)
                .build();
        return DurableToolScheduleResult.builder().invocation(invocation).reused(false).build();
    }

    @Override
    public Optional<DurableToolInvocation> findInvocation(Long toolInvocationId) {
        return queryInvocation("SELECT * FROM tool_invocation WHERE id = ? AND deleted = 0", toolInvocationId);
    }

    @Override
    @Transactional
    public DurableToolAttempt startAttempt(Long toolInvocationId,
                                           String workerId,
                                           long fencingToken,
                                           Instant now,
                                           Instant leaseExpiresAt) {
        DurableToolInvocation invocation = queryInvocation("SELECT * FROM tool_invocation WHERE id = ? AND deleted = 0 FOR UPDATE",
                toolInvocationId).orElseThrow(() -> new IllegalStateException("durable tool invocation does not exist"));
        if (invocation.getFencingToken() != fencingToken || invocation.getStatus() != DurableToolStatus.SCHEDULED) {
            throw new IllegalStateException("durable tool invocation is not schedulable");
        }
        Integer maxAttempt = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM tool_attempt WHERE tool_invocation_id = ? AND deleted = 0",
                Integer.class, toolInvocationId);
        int attemptNo = (maxAttempt == null ? 0 : maxAttempt) + 1;
        jdbcTemplate.update("""
                INSERT INTO tool_attempt (tool_invocation_id, attempt_no, worker_id, fencing_token, status,
                                          started_at, heartbeat_at, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, NOW(), NOW(), 0)
                """, toolInvocationId, attemptNo, workerId, fencingToken, timestamp(now), timestamp(now));
        jdbcTemplate.update("""
                UPDATE tool_invocation
                SET durable_status = 'RUNNING', durable_lease_expires_at = ?, update_time = NOW()
                WHERE id = ? AND durable_status = 'SCHEDULED' AND durable_fencing_token = ? AND deleted = 0
                """, timestamp(leaseExpiresAt), toolInvocationId, fencingToken);
        return DurableToolAttempt.builder()
                .toolInvocationId(toolInvocationId)
                .attemptNo(attemptNo)
                .workerId(workerId)
                .fencingToken(fencingToken)
                .status(DurableToolStatus.RUNNING)
                .startedAt(now)
                .heartbeatAt(now)
                .build();
    }

    @Override
    public boolean heartbeat(Long toolInvocationId,
                             int attemptNo,
                             String workerId,
                             long fencingToken,
                             Instant now,
                             Instant leaseExpiresAt) {
        int updatedAttempt = jdbcTemplate.update("""
                UPDATE tool_attempt SET heartbeat_at = ?, update_time = NOW()
                WHERE tool_invocation_id = ? AND attempt_no = ? AND worker_id = ? AND fencing_token = ?
                  AND status = 'RUNNING' AND deleted = 0
                """, timestamp(now), toolInvocationId, attemptNo, workerId, fencingToken);
        if (updatedAttempt != 1) {
            return false;
        }
        return jdbcTemplate.update("""
                UPDATE tool_invocation SET durable_lease_expires_at = ?, update_time = NOW()
                WHERE id = ? AND durable_fencing_token = ? AND durable_status = 'RUNNING' AND deleted = 0
                """, timestamp(leaseExpiresAt), toolInvocationId, fencingToken) == 1;
    }

    @Override
    @Transactional
    public DurableToolCallbackResult complete(DurableToolWorkerCallback callback) {
        DurableToolInvocation invocation = queryInvocation("SELECT * FROM tool_invocation WHERE id = ? AND deleted = 0 FOR UPDATE",
                callback.getToolInvocationId()).orElse(null);
        if (invocation == null) {
            return DurableToolCallbackResult.NOT_FOUND;
        }
        if (invocation.getFencingToken() != callback.getFencingToken()) {
            return DurableToolCallbackResult.FENCE_REJECTED;
        }
        if (invocation.getStatus().isTerminal()) {
            return DurableToolCallbackResult.DUPLICATE;
        }
        if (invocation.getStatus() != DurableToolStatus.RUNNING
                && invocation.getStatus() != DurableToolStatus.CANCEL_REQUESTED) {
            return DurableToolCallbackResult.INVALID_STATE;
        }
        int attemptUpdated = jdbcTemplate.update("""
                UPDATE tool_attempt
                SET provider_request_id = ?, status = ?, error_type = ?, result_hash = ?,
                    finished_at = ?, heartbeat_at = ?, update_time = NOW()
                WHERE tool_invocation_id = ? AND attempt_no = ? AND worker_id = ? AND fencing_token = ?
                  AND status = 'RUNNING' AND deleted = 0
                """, callback.getProviderRequestId(), callback.getStatus().name(), callback.getErrorType(),
                callback.getResultHash(), timestamp(orNow(callback.getOccurredAt())), timestamp(orNow(callback.getOccurredAt())),
                callback.getToolInvocationId(), callback.getAttemptNo(), callback.getWorkerId(), callback.getFencingToken());
        if (attemptUpdated != 1) {
            return DurableToolCallbackResult.FENCE_REJECTED;
        }
        Instant finishedAt = orNow(callback.getOccurredAt());
        jdbcTemplate.update("""
                UPDATE tool_invocation
                SET durable_status = ?, durable_lease_expires_at = NULL, tool_result = ?, llm_observation = ?,
                    status = ?, error_msg = ?, finished_at = ?, duration_ms = TIMESTAMPDIFF(MICROSECOND, started_at, ?) / 1000,
                    update_time = NOW()
                WHERE id = ? AND durable_fencing_token = ? AND deleted = 0
                """, callback.getStatus().name(), callback.getResultPayload(), callback.getResultPayload(),
                ledgerStatus(callback.getStatus()), callback.getErrorType(), timestamp(finishedAt), timestamp(finishedAt),
                callback.getToolInvocationId(), callback.getFencingToken());
        markOutboxAcknowledged(callback.getToolInvocationId(), finishedAt);
        return DurableToolCallbackResult.ACCEPTED;
    }

    @Override
    public boolean requestCancellation(Long toolInvocationId, long fencingToken, Instant now) {
        return jdbcTemplate.update("""
                UPDATE tool_invocation SET durable_status = 'CANCEL_REQUESTED', update_time = NOW()
                WHERE id = ? AND durable_fencing_token = ?
                  AND durable_status IN ('SCHEDULED', 'RUNNING') AND deleted = 0
                """, toolInvocationId, fencingToken) == 1;
    }

    @Override
    public List<DurableToolOutboxMessage> dueOutbox(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT id, tool_invocation_id, operation_key, status, retry_count, next_attempt_at, published_at, acknowledged_at
                FROM tool_outbox
                WHERE deleted = 0 AND status IN ('SCHEDULED', 'RETRY', 'PUBLISHED') AND next_attempt_at <= ?
                ORDER BY id ASC LIMIT ?
                """, outboxMapper(), timestamp(now), limit);
    }

    @Override
    public void markOutboxPublished(Long outboxId, Instant now) {
        jdbcTemplate.update("""
                UPDATE tool_outbox SET status = 'PUBLISHED', published_at = ?, next_attempt_at = DATE_ADD(?, INTERVAL 30 SECOND),
                    update_time = NOW()
                WHERE id = ? AND status IN ('SCHEDULED', 'RETRY', 'PUBLISHED') AND deleted = 0
                """, timestamp(now), timestamp(now), outboxId);
    }

    @Override
    public void markOutboxRetry(Long outboxId, Instant nextAttemptAt) {
        jdbcTemplate.update("""
                UPDATE tool_outbox SET status = 'RETRY', retry_count = retry_count + 1, next_attempt_at = ?, update_time = NOW()
                WHERE id = ? AND status <> 'ACKNOWLEDGED' AND deleted = 0
                """, timestamp(nextAttemptAt), outboxId);
    }

    @Override
    public void markOutboxAcknowledged(Long toolInvocationId, Instant now) {
        jdbcTemplate.update("""
                UPDATE tool_outbox SET status = 'ACKNOWLEDGED', acknowledged_at = ?, update_time = NOW()
                WHERE tool_invocation_id = ? AND status <> 'ACKNOWLEDGED' AND deleted = 0
                """, timestamp(now), toolInvocationId);
    }

    @Override
    public List<DurableToolInvocation> expiredRunning(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM tool_invocation
                WHERE deleted = 0 AND durable_status = 'RUNNING'
                  AND durable_lease_expires_at IS NOT NULL AND durable_lease_expires_at < ?
                ORDER BY durable_lease_expires_at ASC LIMIT ?
                """, invocationMapper(), timestamp(now), limit);
    }

    @Override
    @Transactional
    public boolean markUnknown(Long toolInvocationId, long fencingToken, String errorType, Instant now) {
        int invocationUpdated = jdbcTemplate.update("""
                UPDATE tool_invocation
                SET durable_status = 'UNKNOWN', durable_lease_expires_at = NULL, status = ?, error_msg = ?,
                    finished_at = ?, duration_ms = TIMESTAMPDIFF(MICROSECOND, started_at, ?) / 1000, update_time = NOW()
                WHERE id = ? AND durable_fencing_token = ? AND durable_status IN ('RUNNING', 'CANCEL_REQUESTED') AND deleted = 0
                """, ExecutionLedgerConstants.STATUS_FAILED, errorType, timestamp(now), timestamp(now), toolInvocationId, fencingToken);
        if (invocationUpdated != 1) {
            return false;
        }
        jdbcTemplate.update("""
                UPDATE tool_attempt SET status = 'UNKNOWN', error_type = ?, finished_at = ?, update_time = NOW()
                WHERE tool_invocation_id = ? AND fencing_token = ? AND status = 'RUNNING' AND deleted = 0
                """, errorType, timestamp(now), toolInvocationId, fencingToken);
        return true;
    }

    private Optional<DurableToolInvocation> findExecutedOperation(Long runId, String operationKey, Long currentId) {
        List<DurableToolInvocation> matches = jdbcTemplate.query("""
                SELECT * FROM tool_invocation
                WHERE run_id = ? AND operation_key = ? AND execution_mode = 'EXECUTED'
                  AND durable_status IS NOT NULL
                  AND id <> ? AND deleted = 0
                ORDER BY id ASC LIMIT 1
                """, invocationMapper(), runId, operationKey, currentId);
        return matches.stream().findFirst();
    }

    private Optional<DurableToolInvocation> queryInvocation(String sql, Object... args) {
        List<DurableToolInvocation> rows = jdbcTemplate.query(sql, invocationMapper(), args);
        return rows.stream().findFirst();
    }

    private RowMapper<DurableToolInvocation> invocationMapper() {
        return (resultSet, rowNum) -> DurableToolInvocation.builder()
                .toolInvocationId(resultSet.getLong("id"))
                .runId(resultSet.getLong("run_id"))
                .toolCallId(resultSet.getString("tool_call_id"))
                .toolName(resultSet.getString("tool_name"))
                .operationKey(resultSet.getString("operation_key"))
                .executionMode(parseMode(resultSet.getString("execution_mode")))
                .sourceInvocationId(nullableLong(resultSet, "source_invocation_id"))
                .status(parseStatus(resultSet.getString("durable_status")))
                .fencingToken(resultSet.getLong("durable_fencing_token"))
                .resultPayload(resultSet.getString("tool_result"))
                .errorType(resultSet.getString("error_msg"))
                .leaseExpiresAt(instant(resultSet, "durable_lease_expires_at"))
                .build();
    }

    private RowMapper<DurableToolOutboxMessage> outboxMapper() {
        return (resultSet, rowNum) -> DurableToolOutboxMessage.builder()
                .id(resultSet.getLong("id"))
                .toolInvocationId(resultSet.getLong("tool_invocation_id"))
                .operationKey(resultSet.getString("operation_key"))
                .status(DurableToolOutboxStatus.valueOf(resultSet.getString("status")))
                .retryCount(resultSet.getInt("retry_count"))
                .nextAttemptAt(instant(resultSet, "next_attempt_at"))
                .publishedAt(instant(resultSet, "published_at"))
                .acknowledgedAt(instant(resultSet, "acknowledged_at"))
                .build();
    }

    private DurableToolStatus parseStatus(String value) {
        return value == null || value.isBlank() ? DurableToolStatus.SCHEDULED : DurableToolStatus.valueOf(value);
    }

    private DurableToolExecutionMode parseMode(String value) {
        return value == null || value.isBlank() ? DurableToolExecutionMode.EXECUTED : DurableToolExecutionMode.valueOf(value);
    }

    private int ledgerStatus(DurableToolStatus status) {
        return switch (status) {
            case SUCCEEDED -> ExecutionLedgerConstants.STATUS_SUCCESS;
            case TIMED_OUT -> ExecutionLedgerConstants.STATUS_TIMEOUT;
            case CANCELLED -> ExecutionLedgerConstants.STATUS_STOPPED;
            default -> ExecutionLedgerConstants.STATUS_FAILED;
        };
    }

    private Instant orNow(Instant value) {
        return value == null ? Instant.now() : value;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }
}
