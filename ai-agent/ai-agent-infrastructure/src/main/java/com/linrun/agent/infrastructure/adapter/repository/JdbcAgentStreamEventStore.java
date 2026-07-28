package com.linrun.agent.infrastructure.adapter.repository;

import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

@Repository
@ConditionalOnBean(name = "mysqlDataSource")
public class JdbcAgentStreamEventStore implements AgentStreamEventStore {

    private final JdbcTemplate jdbc;

    public JdbcAgentStreamEventStore(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void append(String requestId, String eventType, String eventJson) {
        jdbc.update("INSERT INTO agent_stream_event (request_id, event_type, event_json) VALUES (?, ?, ?)",
                requestId, eventType, eventJson);
    }

    @Override
    public List<StoredStreamEvent> findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT sequence_no, event_type, event_json FROM agent_stream_event "
                        + "WHERE request_id = ? ORDER BY sequence_no",
                (rs, rowNum) -> new StoredStreamEvent(
                        rs.getLong("sequence_no"), rs.getString("event_type"), rs.getString("event_json")),
                requestId);
    }
}
