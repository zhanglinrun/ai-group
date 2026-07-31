package com.linrun.agent.infrastructure.adapter.repository;

import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Repository
@ConditionalOnBean(name = "mysqlDataSource")
public class JdbcAgentStreamEventStore implements AgentStreamEventStore {

    private static final int INSERT_RETRY_LIMIT = 8;

    private final JdbcTemplate jdbc;

    public JdbcAgentStreamEventStore(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void append(String requestId, String eventType, String eventJson) {
        appendAndGetSequence(requestId, eventType, eventJson);
    }

    @Override
    public long appendAndGetSequence(String requestId, String eventType, String eventJson) {
        Long runId = jdbc.queryForObject(
                "SELECT id FROM dialogue_run WHERE request_id = ? AND deleted = 0 LIMIT 1",
                Long.class, requestId);
        if (runId == null) {
            throw new IllegalStateException("cannot append stream event for unknown run requestId=" + requestId);
        }
        boolean terminal = isTerminal(eventType);
        for (int attempt = 0; attempt < INSERT_RETRY_LIMIT; attempt++) {
            Long next = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(event_seq), 0) + 1 FROM run_event WHERE run_id = ?",
                    Long.class, runId);
            if (next == null) {
                throw new IllegalStateException("run event sequence allocation returned no value");
            }
            try {
                jdbc.update(
                        "INSERT INTO run_event (run_id, event_seq, event_type, trace_id, span_id, "
                                + "payload_json, payload_summary, payload_hash, terminal_marker) "
                                + "VALUES (?, ?, ?, COALESCE(JSON_UNQUOTE(JSON_EXTRACT(?, '$.traceId')), ?), "
                                + "JSON_UNQUOTE(JSON_EXTRACT(?, '$.spanId')), ?, ?, ?, ?)",
                        runId, next, eventType, eventJson, requestId, eventJson,
                        eventJson, eventType + " canonical event", sha256(eventJson), terminal ? 1 : null);
                return next;
            } catch (DuplicateKeyException duplicate) {
                if (terminal) {
                    Long existingTerminal = jdbc.query(
                            "SELECT event_seq FROM run_event WHERE run_id = ? AND terminal_marker = 1 LIMIT 1",
                            resultSet -> resultSet.next() ? resultSet.getLong(1) : null, runId);
                    if (existingTerminal != null) {
                        return existingTerminal;
                    }
                }
            }
        }
        throw new IllegalStateException("unable to allocate a unique run event sequence requestId=" + requestId);
    }

    @Override
    public List<StoredStreamEvent> findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT run_event.event_seq, run_event.event_type, run_event.payload_json "
                        + "FROM run_event INNER JOIN dialogue_run ON dialogue_run.id = run_event.run_id "
                        + "WHERE dialogue_run.request_id = ? AND dialogue_run.deleted = 0 "
                        + "ORDER BY run_event.event_seq",
                (rs, rowNum) -> new StoredStreamEvent(
                        rs.getLong("event_seq"), rs.getString("event_type"), rs.getString("payload_json")),
                requestId);
    }

    @Override
    public List<StoredStreamEvent> findByRequestIdAfter(String requestId, long afterSequence) {
        return jdbc.query(
                "SELECT run_event.event_seq, run_event.event_type, run_event.payload_json "
                        + "FROM run_event INNER JOIN dialogue_run ON dialogue_run.id = run_event.run_id "
                        + "WHERE dialogue_run.request_id = ? AND dialogue_run.deleted = 0 "
                        + "AND run_event.event_seq > ? ORDER BY run_event.event_seq",
                (rs, rowNum) -> new StoredStreamEvent(
                        rs.getLong("event_seq"), rs.getString("event_type"), rs.getString("payload_json")),
                requestId, Math.max(0L, afterSequence));
    }

    @Override
    public long earliestSequence(String requestId) {
        Long sequence = jdbc.query(
                "SELECT MIN(run_event.event_seq) FROM run_event "
                        + "INNER JOIN dialogue_run ON dialogue_run.id = run_event.run_id "
                        + "WHERE dialogue_run.request_id = ? AND dialogue_run.deleted = 0",
                resultSet -> resultSet.next() ? resultSet.getObject(1, Long.class) : null, requestId);
        return sequence == null ? 0L : sequence;
    }

    @Override
    public long latestSequence(String requestId) {
        Long sequence = jdbc.query(
                "SELECT MAX(run_event.event_seq) FROM run_event "
                        + "INNER JOIN dialogue_run ON dialogue_run.id = run_event.run_id "
                        + "WHERE dialogue_run.request_id = ? AND dialogue_run.deleted = 0",
                resultSet -> resultSet.next() ? resultSet.getObject(1, Long.class) : null, requestId);
        return sequence == null ? 0L : sequence;
    }

    private boolean isTerminal(String eventType) {
        return "complete".equals(eventType) || "error".equals(eventType);
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", unavailable);
        }
    }
}
