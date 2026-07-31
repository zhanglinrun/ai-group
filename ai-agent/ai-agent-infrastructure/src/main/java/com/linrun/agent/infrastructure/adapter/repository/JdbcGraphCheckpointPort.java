package com.linrun.agent.infrastructure.adapter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphCheckpointPort;
import com.linrun.agent.domain.agent.runtime.deepresearch.graph.GraphRunSnapshot;
import com.linrun.agent.types.common.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Durable MySQL checkpoint store for the native SAA DEEP graph.
 *
 * <p>Only {@code DeepResearchCheckpointState} projections arrive at this
 * adapter. The checkpoint table is deliberately independent from legacy
 * LangGraph4j tables so a native run can survive a JVM restart without
 * carrying a framework checkpoint or hidden reasoning data.</p>
 */
@Repository
@Primary
@ConditionalOnBean(name = "mysqlJdbcTemplate")
public class JdbcGraphCheckpointPort implements GraphCheckpointPort {

    private final JdbcTemplate jdbc;

    public JdbcGraphCheckpointPort(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("create table if not exists deep_research_graph_checkpoint ("
                + "graph_id varchar(128) not null, thread_id varchar(128) not null, status varchar(64) not null, "
                + "terminal tinyint not null default 0, checkpoint_state json not null, observed_at datetime(6) not null, "
                + "version bigint not null default 0, primary key (graph_id,thread_id), "
                + "key idx_deep_research_checkpoint_observed (observed_at)) engine=InnoDB default charset=utf8mb4");
    }

    @Override
    public Optional<GraphRunSnapshot> find(String graphId, String threadId) {
        return jdbc.query("select graph_id,thread_id,status,terminal,checkpoint_state,observed_at "
                        + "from deep_research_graph_checkpoint where graph_id=? and thread_id=?",
                resultSet -> resultSet.next()
                        ? Optional.of(new GraphRunSnapshot(resultSet.getString("graph_id"), resultSet.getString("thread_id"),
                        resultSet.getString("status"), resultSet.getBoolean("terminal"), timestamp(resultSet.getTimestamp("observed_at")),
                        parseMap(resultSet.getString("checkpoint_state"))))
                        : Optional.empty(), graphId, threadId);
    }

    @Override
    public void save(GraphRunSnapshot snapshot) {
        jdbc.update("insert into deep_research_graph_checkpoint "
                        + "(graph_id,thread_id,status,terminal,checkpoint_state,observed_at,version) values (?,?,?,?,?,?,0) "
                        + "on duplicate key update status=values(status),terminal=values(terminal),"
                        + "checkpoint_state=values(checkpoint_state),observed_at=values(observed_at),version=version+1",
                snapshot.graphId(), snapshot.threadId(), snapshot.status(), snapshot.terminal() ? 1 : 0,
                JsonUtils.toJson(snapshot.checkpointState()), Timestamp.from(snapshot.observedAt()));
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private static Map<String, Object> parseMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return JsonUtils.parseObject(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
