package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.memory.LongTermMemoryPreference;
import com.linrun.agent.domain.agent.memory.LongTermMemoryService;
import com.linrun.agent.trigger.http.agent.AgentLongTermMemoryController;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class AgentMemoryControlsTest {

    @Test
    public void shouldBindPreferenceAndDeleteRequestsToAuthenticatedOwner() {
        LongTermMemoryService service = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(service.updatePreference(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(service.delete("1001", "memory-1")).thenReturn(true);
        AgentLongTermMemoryController controller = new AgentLongTermMemoryController(service);
        OwnerRequestContext.bind(1001L);
        try {
            Assert.assertNotNull(controller.updatePreference(
                    new AgentLongTermMemoryController.PreferenceRequest(true, 45)).getData());
            Assert.assertTrue(controller.delete("memory-1").getData());
        } finally {
            OwnerRequestContext.clear();
        }
        ArgumentCaptor<LongTermMemoryPreference> preference = ArgumentCaptor.forClass(LongTermMemoryPreference.class);
        Mockito.verify(service).updatePreference(preference.capture());
        Assert.assertEquals("1001", preference.getValue().ownerId());
        Assert.assertEquals(45, preference.getValue().retentionDays());
    }
}
