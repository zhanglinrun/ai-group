package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import com.linrun.agent.domain.agent.model.valobj.AiAgentVO;
import com.linrun.agent.domain.agent.runtime.profile.AgentProfileResolver;
import com.linrun.agent.domain.agent.runtime.profile.ResolvedAgentProfile;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

public class AgentProfileResolverTest {
    @Test
    public void shouldResolveOrderedProcedureAndDistinctClients() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        Mockito.when(repository.queryAvailableFixRoleByAgentId("role-1")).thenReturn(
                AiAgentVO.builder().agentId("role-1").agentName("研究员").description("严谨回答").build());
        Mockito.when(repository.queryAiAgentClientsByAgentId("role-1")).thenReturn(List.of(
                step("client-b", 20, "第二步"), step("client-a", 10, "第一步"), step("client-a", 30, "第三步")));

        ResolvedAgentProfile profile = new AgentProfileResolver(repository).resolve(" role-1 ");

        Assert.assertEquals("1. 第一步\n2. 第二步\n3. 第三步", profile.procedurePrompt());
        Assert.assertEquals(List.of("client-a", "client-b"), profile.clientIds());
        Assert.assertTrue(profile.trustedPrompt().contains("# 当前角色"));
        Assert.assertTrue(profile.trustedPrompt().contains("1. 第一步"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnavailableRoleBeforeAnyProfileStepLookup() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        Mockito.when(repository.queryAvailableFixRoleByAgentId("disabled")).thenReturn(null);
        new AgentProfileResolver(repository).resolve("disabled");
        Mockito.verify(repository, Mockito.never()).queryAiAgentClientsByAgentId(Mockito.anyString());
    }

    @Test
    public void shouldReturnNullForUnboundRequest() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        Assert.assertNull(new AgentProfileResolver(repository).resolve(null));
        Mockito.verifyNoInteractions(repository);
    }

    private AiAgentClientFlowConfigVO step(String clientId, int sequence, String prompt) {
        return AiAgentClientFlowConfigVO.builder().clientId(clientId).sequence(sequence).stepPrompt(prompt).build();
    }
}
