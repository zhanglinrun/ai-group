package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.query.GptQueryIngressService;
import org.wwz.ai.application.agent.quota.AgentRunSettlementService;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.application.agent.visitor.ConversationSessionOwnershipApplicationService;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * GPT 查询入口的 SSE 生命周期与配额结算回归测试。
 */
public class GptQueryIngressServiceTest {

    @Test
    public void shouldCompleteStreamAndConfirmQuotaAfterDispatchSuccess() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        MemberQuotaBillingService billingService = Mockito.mock(MemberQuotaBillingService.class);
        ConversationSessionOwnershipApplicationService ownershipService =
                Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        GptQueryIngressService service = new GptQueryIngressService(
                dispatchService,
                billingService,
                ownershipService,
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(AgentRunSettlementService.class)
        );
        RecordingStream stream = new RecordingStream();
        Mockito.when(billingService.freezeForAgentRun(Mockito.eq(1001L), Mockito.any()))
                .thenReturn("freeze-1");
        OwnerRequestContext.bind(1001L);

        try {
            service.queryAgentStreamIncr(buildReq(), stream);
        } finally {
            OwnerRequestContext.clear();
        }

        Assert.assertTrue(stream.completed);
        Assert.assertFalse(stream.completedWithError);
        Mockito.verify(billingService).confirm("freeze-1");
        Mockito.verify(billingService, Mockito.never()).release("freeze-1");
    }

    @Test
    public void shouldCompleteWithErrorAndReleaseQuotaWhenDispatchFails() throws Exception {
        IAgentDispatchService dispatchService = Mockito.mock(IAgentDispatchService.class);
        MemberQuotaBillingService billingService = Mockito.mock(MemberQuotaBillingService.class);
        ConversationSessionOwnershipApplicationService ownershipService =
                Mockito.mock(ConversationSessionOwnershipApplicationService.class);
        GptQueryIngressService service = new GptQueryIngressService(
                dispatchService,
                billingService,
                ownershipService,
                Mockito.mock(ReactorConfig.class),
                Mockito.mock(AgentRunSettlementService.class)
        );
        RecordingStream stream = new RecordingStream();
        Mockito.when(billingService.freezeForAgentRun(Mockito.eq(1001L), Mockito.any()))
                .thenReturn("freeze-2");
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
        Mockito.verify(billingService).release("freeze-2");
        Mockito.verify(billingService, Mockito.never()).confirm("freeze-2");
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
