package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamPort;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.wwz.ai.domain.agent.runtime.AgentQueryServiceImpl;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
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

    @Test
    public void shouldApplyDeepChatRoutingInLegacyQueryService() {
        ReactorConfig reactorConfig = Mockito.mock(ReactorConfig.class);
        Mockito.when(reactorConfig.getReactorSopPrompt()).thenReturn("plan-sop");
        Mockito.when(reactorConfig.getReactorBasePrompt()).thenReturn("react-base");
        Mockito.when(reactorConfig.getChatDefaultRoleId()).thenReturn("quick-chat-role");
        AgentQueryServiceImpl service = new AgentQueryServiceImpl(
                reactorConfig,
                Map.of(),
                Mockito.mock(RemoteStreamPort.class)
        );

        AgentRequest quickChat = buildAgentRequest(service, "chat", 0);
        Assert.assertEquals(AgentType.WORKFLOW.getValue(), quickChat.getAgentType());
        Assert.assertEquals("quick-chat-role", quickChat.getAiAgentId());

        AgentRequest deepChat = buildAgentRequest(service, "chat", 1);
        Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), deepChat.getAgentType());
        Assert.assertEquals("plan-sop", deepChat.getSopPrompt());
        Assert.assertEquals("chat", deepChat.getOutputStyle());
        Assert.assertNull("Deep Plan-Solve chat must not inherit the Workflow role", deepChat.getAiAgentId());

        AgentRequest quickWeb = buildAgentRequest(service, "web", 0);
        Assert.assertEquals(AgentType.REACT.getValue(), quickWeb.getAgentType());
        Assert.assertEquals("react-base", quickWeb.getBasePrompt());

        AgentRequest deepWeb = buildAgentRequest(service, "web", 1);
        Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), deepWeb.getAgentType());
        Assert.assertEquals("plan-sop", deepWeb.getSopPrompt());
    }

    private AgentRequest buildAgentRequest(AgentQueryServiceImpl service, String outputStyle, Integer deepThink) {
        return ReflectionTestUtils.invokeMethod(
                service,
                "buildAgentRequest",
                GptQueryReq.builder()
                        .traceId("trace-001")
                        .sessionId("session-001")
                        .query("hello")
                        .outputStyle(outputStyle)
                        .deepThink(deepThink)
                        .build()
        );
    }
}
