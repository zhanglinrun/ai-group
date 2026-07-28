package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.service.dispatch.AgentDispatchService;
import com.linrun.agent.domain.agent.service.execute.IExecuteStrategy;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

/** Unified Agent Loop dispatch contract. */
public class AgentDispatchServiceTest {

    @Test
    public void shouldDispatchEveryRequestToUnifiedLoop() throws Exception {
        IExecuteStrategy unified = Mockito.mock(IExecuteStrategy.class);
        AgentDispatchService service = new AgentDispatchService(unified);
        Printer printer = Mockito.mock(Printer.class);

        AgentRequest agentLoop = request(AgentType.AGENT_LOOP);
        AgentRequest defaultRequest = new AgentRequest();
        AgentRequest legacyTypedRequest = new AgentRequest();
        legacyTypedRequest.setAgentType(2);
        AgentRequest legacyAuto = new AgentRequest();
        legacyAuto.setExecutionMode("AUTO");
        service.dispatch(agentLoop, printer);
        service.dispatch(defaultRequest, printer);
        service.dispatch(legacyTypedRequest, printer);
        service.dispatch(legacyAuto, printer);

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        Mockito.verify(unified, Mockito.times(4)).execute(requestCaptor.capture(), Mockito.same(printer));
        List<AgentRequest> dispatchedRequests = requestCaptor.getAllValues();
        Assert.assertSame(agentLoop, dispatchedRequests.get(0));
        Assert.assertSame(defaultRequest, dispatchedRequests.get(1));
        Assert.assertSame(legacyTypedRequest, dispatchedRequests.get(2));
        Assert.assertSame(legacyAuto, dispatchedRequests.get(3));
        Assert.assertEquals(AgentType.AGENT_LOOP.getValue(), legacyTypedRequest.getAgentType());
        Assert.assertEquals("STANDARD", legacyAuto.getExecutionMode());
    }

    private AgentRequest request(AgentType type) {
        AgentRequest request = new AgentRequest();
        request.setAgentType(type.getValue());
        return request;
    }
}
