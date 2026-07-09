package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.quota.MemberQuotaFeignClient;
import org.wwz.ai.application.agent.quota.MemberQuotaResult;
import org.wwz.ai.application.agent.quota.QuotaFreezeVO;
import org.wwz.ai.application.agent.quota.QuotaInsufficientException;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;

/**
 * member-service 配额预扣链路测试。
 */
public class MemberQuotaBillingServiceTest {

    @Test
    public void shouldFreezeQuotaForReactAgent() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingService service = new MemberQuotaBillingService(client);
        AgentRequest request = AgentRequest.builder().agentType(AgentType.REACT.getValue()).build();
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(200);
        result.setData(QuotaFreezeVO.builder().freezeId("freeze-12").amount(1).build());
        Mockito.when(client.freeze(Mockito.any())).thenReturn(result);

        String freezeId = service.freezeForAgentRun(1001L, request);

        Assert.assertEquals("freeze-12", freezeId);
    }

    @Test
    public void shouldFreezeQuotaForWorkflowAgent() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingService service = new MemberQuotaBillingService(client);
        AgentRequest request = AgentRequest.builder().agentType(AgentType.WORKFLOW.getValue()).build();
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(200);
        result.setData(QuotaFreezeVO.builder().freezeId("freeze-wf").amount(1).build());
        Mockito.when(client.freeze(Mockito.argThat(req -> "workflow".equals(req.getAbilityCode())))).thenReturn(result);

        String freezeId = service.freezeForAgentRun(1001L, request);

        Assert.assertEquals("freeze-wf", freezeId);
    }

    @Test(expected = QuotaInsufficientException.class)
    public void shouldRejectWhenQuotaInsufficient() {
        MemberQuotaFeignClient client = Mockito.mock(MemberQuotaFeignClient.class);
        MemberQuotaBillingService service = new MemberQuotaBillingService(client);
        MemberQuotaResult<QuotaFreezeVO> result = new MemberQuotaResult<>();
        result.setCode(621);
        result.setMessage("配额不足");
        Mockito.when(client.freeze(Mockito.any())).thenReturn(result);

        service.freezeForAgentRun(1001L, AgentRequest.builder().build());
    }
}
