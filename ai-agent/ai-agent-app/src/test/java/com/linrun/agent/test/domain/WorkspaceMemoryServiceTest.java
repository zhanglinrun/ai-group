package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.memory.workspace.InMemoryWorkspaceMemoryRepository;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryRepository;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

public class WorkspaceMemoryServiceTest {

    @Test
    public void shouldPersistOnlyExplicitMemoryAndBoundRunLoadToThreeTopics() {
        WorkspaceMemoryService service = service(new InMemoryWorkspaceMemoryRepository());
        service.suggest("tenant-a", "owner-a", "proposal", "must not persist", 0.9D);
        Assert.assertTrue(service.list("tenant-a", "owner-a", 10).isEmpty());

        service.remember("tenant-a", "owner-a", "java", "prefer concise examples", 0.9D, null);
        service.remember("tenant-a", "owner-a", "spring", "use constructor injection", 0.8D, null);
        service.remember("tenant-a", "owner-a", "research", "cite official sources", 1D, null);
        service.remember("tenant-a", "owner-a", "style", "respond in Chinese", 0.7D, null);

        Assert.assertEquals(3, service.loadForRun("tenant-a", "owner-a", List.of()).size());
        Assert.assertEquals(3, service.loadForRun("tenant-a", "owner-a",
                List.of("java", "spring", "research", "style")).size());
    }

    @Test
    public void shouldScopeDeleteExportAndClearByTenantAndOwner() {
        WorkspaceMemoryService service = service(new InMemoryWorkspaceMemoryRepository());
        String id = service.remember("tenant-a", "owner-a", "topic", "private", 1D, null).id();
        service.remember("tenant-a", "owner-b", "topic", "other owner", 1D, null);

        Assert.assertFalse(service.delete("tenant-b", "owner-a", id));
        Assert.assertFalse(service.delete("tenant-a", "owner-b", id));
        Assert.assertEquals(1, service.export("tenant-a", "owner-a").entries().size());
        Assert.assertEquals(1, service.clear("tenant-a", "owner-a"));
        Assert.assertTrue(service.list("tenant-a", "owner-a", 10).isEmpty());
        Assert.assertEquals(1, service.list("tenant-a", "owner-b", 10).size());
    }

    @SuppressWarnings("unchecked")
    private WorkspaceMemoryService service(WorkspaceMemoryRepository repository) {
        ObjectProvider<WorkspaceMemoryRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        return new WorkspaceMemoryService(provider);
    }
}
