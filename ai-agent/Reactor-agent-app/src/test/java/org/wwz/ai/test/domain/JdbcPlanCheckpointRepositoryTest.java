package org.wwz.ai.test.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointPhase;
import org.wwz.ai.domain.agent.checkpoint.PlanCheckpointState;
import org.wwz.ai.domain.agent.checkpoint.PlanExecutionCheckpoint;
import org.wwz.ai.domain.agent.checkpoint.PlanResumeDecision;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.Plan;
import org.wwz.ai.infrastructure.adapter.repository.JdbcPlanCheckpointRepository;

import java.util.List;

public class JdbcPlanCheckpointRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcPlanCheckpointRepository repository;

    @Before
    public void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:checkpoint_repo;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent_run_checkpoint");
        jdbcTemplate.execute("""
                CREATE TABLE agent_run_checkpoint (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  checkpoint_id VARCHAR(64) NOT NULL UNIQUE,
                  run_id BIGINT NOT NULL,
                  request_id VARCHAR(64) NOT NULL,
                  session_id VARCHAR(64) NOT NULL,
                  owner_id VARCHAR(64) NOT NULL,
                  sequence_no INT NOT NULL,
                  phase VARCHAR(32) NOT NULL,
                  step_index INT,
                  snapshot_json CLOB NOT NULL,
                  snapshot_hash CHAR(64) NOT NULL,
                  resumable TINYINT NOT NULL DEFAULT 1,
                  resumed_by_request_id VARCHAR(64),
                  resume_decision VARCHAR(32),
                  resumed_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE (run_id, sequence_no)
                )
                """);
        repository = new JdbcPlanCheckpointRepository(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    public void shouldRoundTripAndAtomicallyClaimCheckpoint() {
        PlanExecutionCheckpoint saved = repository.save(checkpoint());

        Assert.assertNotNull(saved.getCreatedAt());
        Assert.assertNotNull(saved.getSnapshotHash());
        Assert.assertEquals("step one", saved.getState().getNextTask());
        Assert.assertEquals(1, saved.getState().getPlanningMessages().size());

        Assert.assertTrue(repository.claimForResume(
                "cp-1", "7", "session-1", "resume-1", PlanResumeDecision.SAFE_ONLY));
        Assert.assertFalse(repository.claimForResume(
                "cp-1", "7", "session-1", "resume-2", PlanResumeDecision.SAFE_ONLY));
        Assert.assertTrue(repository.claimForResume(
                "cp-1", "7", "session-1", "resume-1", PlanResumeDecision.SAFE_ONLY));

        PlanExecutionCheckpoint claimed = repository.findOwned("cp-1", "7", "session-1").orElseThrow();
        Assert.assertEquals("resume-1", claimed.getResumedByRequestId());
        repository.markRunCompleted(101L);
        Assert.assertFalse(repository.findOwned("cp-1", "7", "session-1").orElseThrow().getResumable());
    }

    @Test
    public void shouldRejectTamperedSnapshot() {
        repository.save(checkpoint());
        jdbcTemplate.update("UPDATE agent_run_checkpoint SET snapshot_json = ? WHERE checkpoint_id = ?",
                "{\"originalQuery\":\"tampered\"}", "cp-1");
        try {
            repository.findOwned("cp-1", "7", "session-1");
            Assert.fail("hash mismatch must reject checkpoint");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("hash mismatch"));
        }
    }

    private PlanExecutionCheckpoint checkpoint() {
        PlanCheckpointState state = PlanCheckpointState.builder()
                .originalQuery("question")
                .nextTask("step one")
                .nextStepIndex(0)
                .plan(Plan.builder()
                        .title("plan")
                        .steps(List.of("step one"))
                        .stepStatus(List.of("in_progress"))
                        .notes(List.of(""))
                        .build())
                .planningMessages(List.of(Message.userMessage("question", null)))
                .build();
        return PlanExecutionCheckpoint.builder()
                .checkpointId("cp-1")
                .runId(101L)
                .requestId("request-1")
                .sessionId("session-1")
                .ownerId("7")
                .sequenceNo(1)
                .phase(PlanCheckpointPhase.READY_FOR_STEP)
                .stepIndex(0)
                .state(state)
                .resumable(Boolean.TRUE)
                .build();
    }
}
