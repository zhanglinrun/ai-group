package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.context.ContextSnapshot;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotConflictException;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotKey;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotRepository;
import com.linrun.agent.domain.agent.runtime.context.ContextProjectionService;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotService;
import com.linrun.agent.domain.agent.runtime.context.InMemoryContextSnapshotRepository;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.harness.AgentHarnessFacade;
import com.linrun.agent.domain.agent.runtime.harness.DefaultAgentHarnessFacade;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

public class ContextSnapshotServiceTest {

    @Test
    public void shouldRevisionPinSnapshotAndRejectStaleWriter() {
        ContextSnapshotService service = service(new InMemoryContextSnapshotRepository());
        ContextSnapshotKey key = new ContextSnapshotKey("tenant-a", "owner-a", "session-a", 7L);
        ContextSnapshot saved = service.save(new ContextSnapshot(key, 0, "research goal", List.of("fact"), List.of(),
                List.of("evidence-1"), List.of(), List.of("write"), List.of("verify citations"),
                "deterministic", "p70", "source", false, 1L, 1L), 0);

        Assert.assertEquals(1L, saved.revision());
        Assert.assertEquals(saved, service.load(key).orElseThrow());
        try {
            service.save(ContextSnapshot.draft(key, "stale overwrite"), 0);
            Assert.fail("stale writer must be rejected");
        } catch (ContextSnapshotConflictException expected) {
            Assert.assertTrue(expected.getMessage().contains("expectedRevision=0"));
        }
        Assert.assertEquals("research goal", service.load(key).orElseThrow().researchGoal());
    }

    @Test
    public void shouldNeverCrossTenantOrOwnerBoundary() {
        ContextSnapshotService service = service(new InMemoryContextSnapshotRepository());
        ContextSnapshotKey original = new ContextSnapshotKey("tenant-a", "owner-a", "session-a", 7L);
        service.save(ContextSnapshot.draft(original, "private goal"), 0);

        Assert.assertTrue(service.load(new ContextSnapshotKey("tenant-b", "owner-a", "session-a", 7L)).isEmpty());
        Assert.assertTrue(service.load(new ContextSnapshotKey("tenant-a", "owner-b", "session-a", 7L)).isEmpty());
    }

    @Test
    public void shouldPinSnapshotRevisionAndHashIntoHarnessProjectionForReplay() {
        ContextSnapshotService snapshots = service(new InMemoryContextSnapshotRepository());
        DefaultAgentHarnessFacade facade = new DefaultAgentHarnessFacade(Mockito.mock(AgentLoopFactory.class));
        ReflectionTestUtils.setField(facade, "contextSnapshotService", snapshots);
        ReflectionTestUtils.setField(facade, "contextProjectionService", new ContextProjectionService());
        com.linrun.agent.domain.agent.ledger.model.AgentRunState state =
                com.linrun.agent.domain.agent.ledger.model.AgentRunState.builder().build();
        state.setRunId(17L);
        state.markExecutionPosition("researcher", 1);
        AgentContext context = AgentContext.builder()
                .tenantId("tenant-a")
                .ownerId(1001L)
                .sessionId("session-a")
                .requestId("request-a")
                .query("Compare official market evidence")
                .task("Find primary sources")
                .agentRunState(state)
                .build();

        AgentHarnessFacade.ContextProjection first = facade.projectContext(context);
        AgentHarnessFacade.ContextProjection replay = facade.projectContext(context);
        ContextSnapshot stored = snapshots.load(new ContextSnapshotKey("tenant-a", "1001", "session-a", 17L))
                .orElseThrow();

        Assert.assertEquals(1L, stored.revision());
        Assert.assertTrue(first.systemPrompt().contains("snapshot_revision=\"1\""));
        Assert.assertTrue(first.systemPrompt().contains(stored.snapshotHash()));
        Assert.assertTrue(first.systemPrompt().contains("CURRENT_USER_REQUEST"));
        Assert.assertTrue(first.systemPrompt().contains("Compare official market evidence"));
        Assert.assertEquals(first.systemPrompt(), replay.systemPrompt());
    }

    @SuppressWarnings("unchecked")
    private ContextSnapshotService service(ContextSnapshotRepository repository) {
        ObjectProvider<ContextSnapshotRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        return new ContextSnapshotService(provider);
    }
}
