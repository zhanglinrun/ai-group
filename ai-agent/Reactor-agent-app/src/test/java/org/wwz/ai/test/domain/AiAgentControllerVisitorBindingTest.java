package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.query.IGptQueryApplicationService;
import org.wwz.ai.application.agent.visitor.ConversationSessionOwnershipApplicationService;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.trigger.http.AiAgentController;
import org.wwz.ai.types.agent.config.AgentExecutorProperties;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * AiAgentController owner 绑定与异步派发测试。
 */
public class AiAgentControllerVisitorBindingTest {

    @Test
    public void shouldBindSessionBeforeDispatchingAutoAgent() throws Exception {
        AiAgentController controller = new AiAgentController();
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ConversationSessionOwnershipApplicationService ownershipService = Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        MemberQuotaBillingService quotaBillingService = Mockito.mock(MemberQuotaBillingService.class);
        ReflectionTestUtils.setField(controller, "agentDispatchService", dispatchService);
        ReflectionTestUtils.setField(controller, "gptQueryApplicationService", Mockito.mock(IGptQueryApplicationService.class));
        ReflectionTestUtils.setField(controller, "conversationSessionOwnershipApplicationService", ownershipService);
        ReflectionTestUtils.setField(controller, "agentExecutorProperties", new AgentExecutorProperties());
        ReflectionTestUtils.setField(controller, "dispatchExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(controller, "heartbeatScheduler", Mockito.mock(TaskScheduler.class));

        AgentRequest request = AgentRequest.builder()
                .requestId("req-001")
                .sessionId("session-001")
                .query("帮我总结一下这个项目")
                .build();
        Mockito.when(ownershipService.ensureSessionAccessible("1001", "session-001", "帮我总结一下这个项目"))
                .thenReturn(DialogueSession.builder().sessionId("session-001").ownerId("1001").build());
        CountDownLatch latch = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(dispatchService).dispatch(Mockito.eq(request), Mockito.any());

        OwnerRequestContext.bind(1001L);
        try {
            SseEmitter emitter = controller.AutoAgent(request);
            Assert.assertNotNull(emitter);
        } finally {
            OwnerRequestContext.clear();
        }

        Mockito.verify(ownershipService).ensureSessionAccessible("1001", "session-001", "帮我总结一下这个项目");
        Assert.assertEquals("1001", request.getOwnerId());
        Assert.assertTrue("异步派发应已触发", latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    public void shouldSurfaceDispatchFailureWithoutRunLevelQuota() throws Exception {
        AiAgentController controller = new AiAgentController();
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ConversationSessionOwnershipApplicationService ownershipService = Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        MemberQuotaBillingService quotaBillingService = Mockito.mock(MemberQuotaBillingService.class);
        ReflectionTestUtils.setField(controller, "agentDispatchService", dispatchService);
        ReflectionTestUtils.setField(controller, "gptQueryApplicationService", Mockito.mock(IGptQueryApplicationService.class));
        ReflectionTestUtils.setField(controller, "conversationSessionOwnershipApplicationService", ownershipService);
        ReflectionTestUtils.setField(controller, "agentExecutorProperties", new AgentExecutorProperties());
        ReflectionTestUtils.setField(controller, "dispatchExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(controller, "heartbeatScheduler", Mockito.mock(TaskScheduler.class));

        AgentRequest request = AgentRequest.builder()
                .requestId("req-003")
                .sessionId("session-003")
                .query("触发失败回滚")
                .build();
        Mockito.when(ownershipService.ensureSessionAccessible("1003", "session-003", "触发失败回滚"))
                .thenReturn(org.wwz.ai.domain.agent.ledger.entity.DialogueSession.builder()
                        .sessionId("session-003").ownerId("1003").build());
        Mockito.doThrow(new RuntimeException("dispatch failed"))
                .when(dispatchService).dispatch(Mockito.eq(request), Mockito.any());

        OwnerRequestContext.bind(1003L);
        try {
            controller.AutoAgent(request);
            Thread.sleep(200);
        } finally {
            OwnerRequestContext.clear();
        }

    }

    @Test
    public void shouldPreferGatewayOwnerOverCallerSuppliedValue() throws Exception {
        AiAgentController controller = new AiAgentController();
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        ConversationSessionOwnershipApplicationService ownershipService = Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        MemberQuotaBillingService quotaBillingService = Mockito.mock(MemberQuotaBillingService.class);
        ReflectionTestUtils.setField(controller, "agentDispatchService", dispatchService);
        ReflectionTestUtils.setField(controller, "gptQueryApplicationService", Mockito.mock(IGptQueryApplicationService.class));
        ReflectionTestUtils.setField(controller, "conversationSessionOwnershipApplicationService", ownershipService);
        ReflectionTestUtils.setField(controller, "agentExecutorProperties", new AgentExecutorProperties());
        ReflectionTestUtils.setField(controller, "dispatchExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(controller, "heartbeatScheduler", Mockito.mock(TaskScheduler.class));

        AgentRequest request = AgentRequest.builder()
                .requestId("req-002")
                .sessionId("session-002")
                .ownerId("9999")
                .query("继续这个会话")
                .build();
        Mockito.when(ownershipService.ensureSessionAccessible("1002", "session-002", "继续这个会话"))
                .thenReturn(DialogueSession.builder().sessionId("session-002").ownerId("1002").build());

        OwnerRequestContext.bind(1002L);
        try {
            controller.AutoAgent(request);
        } finally {
            OwnerRequestContext.clear();
        }

        Assert.assertEquals("1002", request.getOwnerId());
        Mockito.verify(ownershipService).ensureSessionAccessible("1002", "session-002", "继续这个会话");
    }
}
