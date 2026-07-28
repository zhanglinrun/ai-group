package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.service.dispatch.IAgentDispatchService;
import com.linrun.agent.domain.agent.adapter.port.ModelCatalogPort;
import com.linrun.agent.trigger.service.GptQueryIngressService;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.reactor.model.req.GptQueryReq;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * GPT 查询入口的 SSE 生命周期与配额结算回归测试。
 */
public class GptQueryIngressServiceTest {

    @Test
    public void shouldCompleteStreamWithoutRunLevelQuotaAfterDispatchSuccess() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ConversationSessionOwnershipService ownershipService =
                Mockito.mock(ConversationSessionOwnershipService.class);
        GptQueryIngressService service = new GptQueryIngressService(
                dispatchService,
                ownershipService,
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(ModelCatalogPort.class)
        );
        RecordingStream stream = new RecordingStream();
        OwnerRequestContext.bind(1001L);

        try {
            service.queryAgentStreamIncr(buildReq(), stream);
        } finally {
            OwnerRequestContext.clear();
        }

        Assert.assertTrue(stream.completed);
        Assert.assertFalse(stream.completedWithError);
    }

    @Test
    public void shouldCompleteWithErrorWithoutRunLevelQuotaWhenDispatchFails() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ConversationSessionOwnershipService ownershipService =
                Mockito.mock(ConversationSessionOwnershipService.class);
        GptQueryIngressService service = new GptQueryIngressService(
                dispatchService,
                ownershipService,
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(ModelCatalogPort.class)
        );
        RecordingStream stream = new RecordingStream();
        Mockito.doThrow(new IllegalStateException("dispatch failed"))
                .when(dispatchService)
                .dispatch(Mockito.any(), Mockito.any());
        OwnerRequestContext.bind(1001L);

        try {
            service.queryAgentStreamIncr(buildReq(), stream);
            Assert.fail("dispatch 异常应继续向上抛出");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("dispatch failed", expected.getMessage());
        } finally {
            OwnerRequestContext.clear();
        }

        Assert.assertFalse(stream.completed);
        Assert.assertTrue(stream.completedWithError);
    }

    @Test
    public void shouldRouteEveryExecutionModeToUnifiedAgentLoop() {
        ReactorConfig reactorConfig = Mockito.mock(ReactorConfig.class);
        Mockito.when(reactorConfig.getReactorBasePrompt()).thenReturn("react-base");
        GptQueryIngressService service = new GptQueryIngressService(
                Mockito.mock(IAgentDispatchService.class),
                Mockito.mock(ConversationSessionOwnershipService.class),
                reactorConfig,
                Mockito.mock(ModelCatalogPort.class)
        );
        OwnerRequestContext.bind(1001L);
        try {
            AgentRequest auto = prepareRoute(service, "web", "AUTO");
            assertUnifiedRoute(auto, "AUTO", "web");

            AgentRequest standard = prepareRoute(service, "chat", "STANDARD");
            assertUnifiedRoute(standard, "STANDARD", "chat");

            AgentRequest deep = prepareRoute(service, "docs", "DEEP");
            assertUnifiedRoute(deep, "DEEP", "docs");
        } finally {
            OwnerRequestContext.clear();
        }
    }

    @Test
    public void shouldNormalizeExecutionModeAndDefaultInvalidValuesToStandard() {
        ReactorConfig reactorConfig = Mockito.mock(ReactorConfig.class);
        Mockito.when(reactorConfig.getReactorBasePrompt()).thenReturn("react-base");
        GptQueryIngressService service = new GptQueryIngressService(
                Mockito.mock(IAgentDispatchService.class),
                Mockito.mock(ConversationSessionOwnershipService.class),
                reactorConfig,
                Mockito.mock(ModelCatalogPort.class)
        );
        OwnerRequestContext.bind(1001L);
        try {
            Assert.assertEquals("DEEP", prepareRoute(service, "web", "  deep ").getExecutionMode());
            Assert.assertEquals("STANDARD", prepareRoute(service, "web", "unsupported").getExecutionMode());
            Assert.assertEquals("STANDARD", prepareRoute(service, "web", null).getExecutionMode());
            Assert.assertEquals("markdown", prepareRoute(service, null, "DEEP").getOutputStyle());
        } finally {
            OwnerRequestContext.clear();
        }
    }

    private AgentRequest prepareRoute(GptQueryIngressService service,
                                      String outputStyle,
                                      String executionMode) {
        GptQueryReq request = buildReq();
        request.setOutputStyle(outputStyle);
        request.setExecutionMode(executionMode);
        return service.prepare(request, new RecordingStream()).agentRequest();
    }

    private void assertUnifiedRoute(AgentRequest request, String executionMode, String outputStyle) {
        Assert.assertEquals(AgentType.AGENT_LOOP.getValue(), request.getAgentType());
        Assert.assertEquals(executionMode, request.getExecutionMode());
        Assert.assertEquals(AgentExecutionProfile.valueOf(executionMode), request.resolveExecutionProfile());
        Assert.assertEquals(outputStyle, request.getOutputStyle());
        Assert.assertEquals("react-base", request.getBasePrompt());
    }

    private GptQueryReq buildReq() {
        return GptQueryReq.builder()
                .sessionId("session-1")
                .requestId("req-1")
                .query("你好")
                .outputStyle("web")
                .build();
    }

    private static class RecordingStream implements AgentMessageStream {
        private final List<Object> payloads = new ArrayList<>();
        private boolean completed;
        private boolean completedWithError;

        @Override
        public void send(Object payload) {
            payloads.add(payload);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable throwable) {
            completedWithError = true;
        }
    }
}
