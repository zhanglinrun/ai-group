package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.domain.agent.reactor.service.EmbeddingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PgVectorMemoryRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbcTemplate;
    static PgVectorMemoryRepository repository;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(findDdl()));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(findMemoryControlsMigration()));
        }
        EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
        Mockito.when(embeddingService.getVector(Mockito.anyString()))
                .thenReturn(Collections.nCopies(1024, 0.1f));
        repository = new PgVectorMemoryRepository(jdbcTemplate, embeddingService);
    }

    @Test
    void validatesDdlRetrievalIsolationAndRollback() {
        assertEquals(1024, jdbcTemplate.queryForObject(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid='agent_semantic_memory'::regclass AND attname='embedding'",
                Integer.class));
        assertTrue(repository.saveMemory("memory-a", "owner-1", "qa_pair",
                "Java 虚拟线程适合高并发 IO", Map.of(), "conversation-a"));
        assertTrue(repository.saveMemory("memory-b", "owner-1", "qa_pair",
                "Kafka 可靠投递使用幂等生产者", Map.of(), "conversation-b"));
        assertTrue(repository.saveMemory("memory-other", "owner-2", "qa_pair",
                "Java 虚拟线程", Map.of(), "conversation-a"));

        List<Map<String, Object>> vectorHits = repository.recallByVector(
                "owner-1", "虚拟线程", List.of("qa_pair"), 10, 0.1d);
        List<Map<String, Object>> keywordHits = repository.recallByKeyword(
                "owner-1", "虚拟线程", List.of("qa_pair"), 10);
        assertFalse(vectorHits.isEmpty());
        assertTrue(keywordHits.stream().anyMatch(row -> "memory-a".equals(row.get("id"))));
        assertEquals(1, repository.findByOwnerDocTypeAndConversation(
                "owner-1", "qa_pair", "conversation-a", 10).size());

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(repository.saveMemory("rollback-memory", "owner-1", "qa_pair",
                    "should rollback", Map.of(), "conversation-a"));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_semantic_memory WHERE id='rollback-memory'", Integer.class));
    }

    private static Path findDdl() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("docs/dev-ops/postgres/sql/01-agent-memory.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("pgvector DDL not found");
    }

    private static Path findMemoryControlsMigration() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("docs/dev-ops/postgres/sql/02-agent-long-term-memory-controls.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("long-term memory controls migration not found");
    }
}
