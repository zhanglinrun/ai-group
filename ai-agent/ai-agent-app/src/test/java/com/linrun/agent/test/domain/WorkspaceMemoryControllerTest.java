package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.memory.workspace.InMemoryWorkspaceMemoryRepository;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryRepository;
import com.linrun.agent.domain.agent.memory.workspace.WorkspaceMemoryService;
import com.linrun.agent.trigger.http.agent.AgentWorkspaceMemoryController;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

public class WorkspaceMemoryControllerTest {

    @After
    public void clearOwnerContext() {
        OwnerRequestContext.clear();
    }

    @Test
    public void shouldKeepSuggestionsEphemeralAndEnforceOwnerBoundaryForEveryOperation() {
        AgentWorkspaceMemoryController controller = new AgentWorkspaceMemoryController(service());
        OwnerRequestContext.bind(1001L);

        controller.suggest(new AgentWorkspaceMemoryController.SuggestionRequest("style", "concise", 0.8D));
        Assert.assertTrue(controller.list(null).getData().isEmpty());
        String memoryId = controller.remember(
                new AgentWorkspaceMemoryController.RememberRequest("style", "concise", 0.9D, 30))
                .getData().id();

        OwnerRequestContext.bind(2002L);
        Assert.assertTrue(controller.list(null).getData().isEmpty());
        Assert.assertFalse(controller.delete(memoryId).getData());
        Assert.assertTrue(controller.export().getData().entries().isEmpty());

        OwnerRequestContext.bind(1001L);
        Assert.assertEquals(1, controller.list(null).getData().size());
        Assert.assertTrue(controller.delete(memoryId).getData());
        Assert.assertTrue(controller.list(null).getData().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private WorkspaceMemoryService service() {
        WorkspaceMemoryRepository repository = new InMemoryWorkspaceMemoryRepository();
        ObjectProvider<WorkspaceMemoryRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        return new WorkspaceMemoryService(provider);
    }
}
