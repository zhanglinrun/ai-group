package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Locks the retired runtime boundary to read-only ledger replay. */
public class RetiredRuntimeBoundaryTest {

    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final Path DOMAIN_MAIN = PROJECT_ROOT.resolve("ai-agent-domain/src/main/java");
    private static final String REPLAY_ADAPTER =
            "ai-agent-domain/src/main/java/com/linrun/agent/domain/agent/ledger/replay/LegacyLedgerReplayCompatibility.java";

    @Test
    public void shouldExposeOnlyTheUnifiedAgentLoopType() {
        Assert.assertArrayEquals(new AgentType[]{AgentType.AGENT_LOOP}, AgentType.values());
        String dispatchSource = read(DOMAIN_MAIN.resolve(
                "com/linrun/agent/domain/agent/service/dispatch/AgentDispatchService.java"));
        Assert.assertTrue(dispatchSource.contains("AgentType.AGENT_LOOP"));
        Assert.assertFalse(dispatchSource.contains("getStrategy("));
    }

    @Test
    public void shouldKeepRetiredLedgerIdentifiersInsideReplayOnly() throws IOException {
        Assert.assertEquals(List.of(REPLAY_ADAPTER), findMainJavaContaining("\"plan_solve\""));
        Assert.assertEquals(List.of(REPLAY_ADAPTER), findMainJavaContaining("\"react\""));

        Path sharedConstants = DOMAIN_MAIN.resolve(
                "com/linrun/agent/domain/agent/ledger/model/ExecutionLedgerConstants.java");
        Assert.assertFalse(Files.readString(sharedConstants, StandardCharsets.UTF_8).contains("LEGACY_"));
    }

    @Test
    public void shouldRemoveRetiredWorkflowManagementSurface() throws IOException {
        Assert.assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "ai-agent-trigger/src/main/java/com/linrun/agent/trigger/http/admin/AiAgentDrawAdminController.java")));
        Assert.assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "ai-agent-api/src/main/java/com/linrun/agent/api/IAiAgentDrawAdminService.java")));
        Assert.assertFalse(Files.exists(DOMAIN_MAIN.resolve(
                "com/linrun/agent/domain/agent/model/entity/ExecuteCommandEntity.java")));
        Assert.assertFalse(Files.exists(DOMAIN_MAIN.resolve(
                "com/linrun/agent/domain/agent/model/entity/AgentExecuteResultEntity.java")));

        Path triggerMain = PROJECT_ROOT.resolve("ai-agent-trigger/src/main/java");
        try (Stream<Path> paths = Files.walk(triggerMain)) {
            boolean hasRetiredRoute = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .anyMatch(path -> read(path).contains("/ai-agent-draw"));
            Assert.assertFalse("retired workflow management route must not return", hasRetiredRoute);
        }
    }

    private List<String> findMainJavaContaining(String needle) throws IOException {
        try (Stream<Path> paths = Files.walk(PROJECT_ROOT)) {
            return paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("src\\main\\java")
                            || path.toString().replace('\\', '/').contains("src/main/java"))
                    .filter(path -> read(path).contains(needle))
                    .map(path -> PROJECT_ROOT.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("failed to read " + path, error);
        }
    }

    private static Path resolveProjectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("ai-agent-domain"))
                    && Files.exists(current.resolve("ai-agent-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate ai-agent project root");
    }
}
