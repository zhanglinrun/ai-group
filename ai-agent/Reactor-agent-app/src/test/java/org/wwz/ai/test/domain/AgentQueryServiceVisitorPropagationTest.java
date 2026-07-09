package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamPort;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.wwz.ai.domain.agent.runtime.AgentQueryServiceImpl;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

/**
 * AgentQueryService owner 传播测试。
 */
public class AgentQueryServiceVisitorPropagationTest {

    @Test
    public void shouldPropagateOwnerIdIntoBuiltAgentRequest() {
        AgentQueryServiceImpl service = new AgentQueryServiceImpl(
                Mockito.mock(ReactorConfig.class),
                Map.of(),
                Mockito.mock(RemoteStreamPort.class)
        );
        OwnerRequestContext.bind(1001L);
        try {
            AgentRequest request = ReflectionTestUtils.invokeMethod(
                    service,
                    "buildAgentRequest",
                    GptQueryReq.builder()
                            .traceId("trace-001")
                            .sessionId("session-001")
                            .query("hello")
                            .build()
            );
            Assert.assertNotNull(request);
            Assert.assertEquals("1001", request.getOwnerId());
        } finally {
            OwnerRequestContext.clear();
        }
    }
}
