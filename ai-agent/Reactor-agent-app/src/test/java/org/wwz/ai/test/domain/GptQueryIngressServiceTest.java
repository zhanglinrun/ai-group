package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.model.IModelCatalogQueryService;
import org.wwz.ai.application.agent.query.GptQueryIngressService;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.application.agent.visitor.ConversationSessionOwnershipApplicationService;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * GPT 查询入口的 SSE 生命周期与配额结算回归测试。
 */
public class GptQueryIngressServiceTest {

    @Test
    public void shouldCompleteStreamWithoutRunLevelQuotaAfterDispatchSuccess() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        MemberQuotaBillingService billingService = Mockito.mock(MemberQuotaBillingService.class);
        ConversationSessionOwnershipApplicationService ownershipService =
                Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        GptQueryIngressService service = new GptQueryIngressService(
                dispatchService,
                ownershipService,
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(IModelCatalogQueryService.class)
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
        MemberQuotaBillingService billingService = Mockito.mock(MemberQuotaBillingService.class);
        ConversationSessionOwnershipApplicationService ownershipService =
                Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        GptQueryIngressService service = new GptQueryIngressService(
                dispatchService,
                ownershipService,
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(IModelCatalogQueryService.class)
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
    public void shouldPropagateCheckpointResumeOnlyForPlanSolve() {
        GptQueryIngressService service = new GptQueryIngressService(
                Mockito.mock(IAgentDispatchService.class),
                Mockito.mock(ConversationSessionOwnershipApplicationService.class),
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(IModelCatalogQueryService.class)
        );
        GptQueryReq request = buildReq();
        request.setDeepThink(1);
        request.setResumeCheckpointId("checkpoint-1");
        request.setResumeDecision("RESTART_FROM_CHECKPOINT");
        OwnerRequestContext.bind(1001L);
        try {
            AgentRequest agentRequest = service.prepare(request, new RecordingStream()).agentRequest();
            Assert.assertEquals("checkpoint-1", agentRequest.getResumeCheckpointId());
            Assert.assertEquals("RESTART_FROM_CHECKPOINT", agentRequest.getResumeDecision());
            Assert.assertEquals(Integer.valueOf(3), agentRequest.getAgentType());
        } finally {
            OwnerRequestContext.clear();
        }
    }

    @Test
    public void shouldRejectCheckpointResumeForReactMode() {
        GptQueryIngressService service = new GptQueryIngressService(
                Mockito.mock(IAgentDispatchService.class),
                Mockito.mock(ConversationSessionOwnershipApplicationService.class),
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(IModelCatalogQueryService.class)
        );
        GptQueryReq request = buildReq();
        request.setDeepThink(0);
        request.setResumeCheckpointId("checkpoint-1");
        OwnerRequestContext.bind(1001L);
        try {
            service.prepare(request, new RecordingStream());
            Assert.fail("React mode must not silently ignore a Plan-Solve checkpoint");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Plan-Solve"));
        } finally {
            OwnerRequestContext.clear();
        }
    }

    @Test
    public void shouldRouteDeepChatToPlanSolveAndKeepQuickChatOnWorkflow() {
        ReactorConfig reactorConfig = Mockito.mock(ReactorConfig.class);
        Mockito.when(reactorConfig.getReactorSopPrompt()).thenReturn("plan-sop");
        Mockito.when(reactorConfig.getReactorBasePrompt()).thenReturn("react-base");
        Mockito.when(reactorConfig.getChatDefaultRoleId()).thenReturn("quick-chat-role");
        GptQueryIngressService service = new GptQueryIngressService(
                Mockito.mock(IAgentDispatchService.class),
                Mockito.mock(ConversationSessionOwnershipApplicationService.class),
                reactorConfig,
                Mockito.mock(IModelCatalogQueryService.class)
        );
        OwnerRequestContext.bind(1001L);
        try {
            AgentRequest quickChat = prepareRoute(service, "chat", 0);
            Assert.assertEquals(AgentType.WORKFLOW.getValue(), quickChat.getAgentType());
            Assert.assertEquals("quick-chat-role", quickChat.getAiAgentId());

            AgentRequest deepChat = prepareRoute(service, "chat", 1);
            Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), deepChat.getAgentType());
            Assert.assertEquals("plan-sop", deepChat.getSopPrompt());
            Assert.assertEquals("chat", deepChat.getOutputStyle());
            Assert.assertNull("Deep Plan-Solve chat must not inherit the Workflow role", deepChat.getAiAgentId());

            AgentRequest quickWeb = prepareRoute(service, "web", 0);
            Assert.assertEquals(AgentType.REACT.getValue(), quickWeb.getAgentType());
            Assert.assertEquals("react-base", quickWeb.getBasePrompt());

            AgentRequest deepWeb = prepareRoute(service, "web", 1);
            Assert.assertEquals(AgentType.PLAN_SOLVE.getValue(), deepWeb.getAgentType());
            Assert.assertEquals("plan-sop", deepWeb.getSopPrompt());
        } finally {
            OwnerRequestContext.clear();
        }
    }

    private AgentRequest prepareRoute(GptQueryIngressService service, String outputStyle, Integer deepThink) {
        GptQueryReq request = buildReq();
        request.setOutputStyle(outputStyle);
        request.setDeepThink(deepThink);
        return service.prepare(request, new RecordingStream()).agentRequest();
    }

    private GptQueryReq buildReq() {
        return GptQueryReq.builder()
                .sessionId("session-1")
                .requestId("req-1")
                .query("你好")
                .outputStyle("web")
                .build();
    }

    private static class RecordingStream implements AgentSessionStream {
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
