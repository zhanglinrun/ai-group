package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.llm.LLM;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CompletionException;

/** Quota admission must reject an unaffordable call before any provider admission. */
public class LlmQuotaAdmissionTest {

    @Test
    public void shouldRejectUnaffordableReservationBeforeProviderAdmission() {
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        QuotaBillingPort quota = Mockito.mock(QuotaBillingPort.class);
        Mockito.when(recorder.createLlmInvocation(Mockito.any())).thenReturn(91L);
        Mockito.when(quota.reserve(Mockito.eq(1001L), Mockito.anyLong(), Mockito.anyLong(),
                        Mockito.eq("req-quota-admission:llm:91")))
                .thenReturn(new QuotaBillingPort.Reservation("freeze-too-small", 1L));

        ReactorRuntimeDependencies dependencies = ReactorRuntimeTestSupport.runtimeDependencies(new ReactorConfig())
                .toBuilder()
                .quotaBillingPort(quota)
                .build();
        AgentContext context = AgentContext.builder()
                .requestId("req-quota-admission")
                .sessionId("session-quota-admission")
                .ownerId(1001L)
                .executionRecorder(recorder)
                .agentRunState(AgentRunState.builder().runId(1L).build())
                .runtimeDependencies(dependencies)
                .build();
        LLM llm = new LLM(LLMSettings.builder()
                .model("test-model")
                .maxTokens(512)
                .maxInputTokens(4096)
                .baseUrl("http://localhost")
                .apiKey("test-key")
                .functionCallType("function")
                .build(), "", dependencies);

        try {
            llm.ask(context, List.of(Message.userMessage("short request", null)), List.of(), false, 0D).join();
            Assert.fail("expected quota insufficiency");
        } catch (CompletionException failure) {
            Assert.assertTrue(failure.getCause() instanceof QuotaInsufficientException);
        }

        Mockito.verify(quota).release("freeze-too-small");
        Mockito.verify(quota, Mockito.never()).markProviderStarted(Mockito.anyString());
    }
}
