package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import com.linrun.agent.domain.agent.runtime.llm.BillableModelInvocationService;
import com.linrun.agent.domain.agent.runtime.llm.ModelInvocationPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedModelInvocationIT {

    @Test
    void billsUserVlmThroughExistingQuotaPortAndWritesUserOwner() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        QuotaBillingPort quota = mock(QuotaBillingPort.class);
        ChatModel model = mock(ChatModel.class);
        when(recorder.createLlmInvocation(any())).thenReturn(41L);
        when(quota.reserve(eq(7L), anyLong(), anyLong(), eq("vlm_describe"), eq("req-1:llm:41")))
                .thenReturn(new QuotaBillingPort.Reservation("freeze-1", 200_000L));
        when(model.call(any(Prompt.class))).thenReturn(response("image description"));

        BillableModelInvocationService service = new BillableModelInvocationService(recorder, quota);
        service.invoke(model, prompt("describe image"), new ModelInvocationPolicy(
                ModelInvocationPolicy.CostOwner.USER_QUOTA, 7L, 9L, "req-1", "agent_loop", 2,
                "vlm_describe", "qwen-vl-plus", 800, 1024, 5L, 30L, 0D));

        ArgumentCaptor<LlmInvocationStartRecord> start = ArgumentCaptor.forClass(LlmInvocationStartRecord.class);
        verify(recorder).createLlmInvocation(start.capture());
        org.junit.jupiter.api.Assertions.assertEquals("USER_QUOTA", start.getValue().getCostOwner());
        verify(quota).markProviderStarted("freeze-1");
        verify(quota).settleWithUsage(eq("freeze-1"), anyLong(), any());
        ArgumentCaptor<LlmInvocationFinishRecord> finish = ArgumentCaptor.forClass(LlmInvocationFinishRecord.class);
        verify(recorder).finishLlmInvocation(finish.capture());
        org.junit.jupiter.api.Assertions.assertEquals(1, finish.getValue().getStatus());
    }

    @Test
    void recordsPlatformSummaryWithoutMemberQuotaCalls() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        QuotaBillingPort quota = mock(QuotaBillingPort.class);
        ChatModel model = mock(ChatModel.class);
        when(recorder.createLlmInvocation(any())).thenReturn(42L);
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));

        new BillableModelInvocationService(recorder, quota).invoke(model, prompt("summarize"),
                ModelInvocationPolicy.platformCost(9L, "req-2", "memory_summary", "qwen-plus",
                        800, 20, 5L, 30L, 0D));

        ArgumentCaptor<LlmInvocationStartRecord> start = ArgumentCaptor.forClass(LlmInvocationStartRecord.class);
        verify(recorder).createLlmInvocation(start.capture());
        org.junit.jupiter.api.Assertions.assertEquals("PLATFORM_COST", start.getValue().getCostOwner());
        verify(quota, never()).reserve(any(), anyLong(), anyLong(), any());
        verify(quota, never()).markProviderStarted(any());
        verify(quota, never()).settleWithUsage(any(), anyLong(), any());
    }

    @Test
    void leavesProviderStartedFreezeForRecoveryWhenProviderOutcomeIsUnknown() {
        AgentExecutionRecorder recorder = mock(AgentExecutionRecorder.class);
        QuotaBillingPort quota = mock(QuotaBillingPort.class);
        ChatModel model = mock(ChatModel.class);
        when(recorder.createLlmInvocation(any())).thenReturn(43L);
        when(quota.reserve(eq(7L), anyLong(), anyLong(), eq("vlm_describe"), eq("req-3:llm:43")))
                .thenReturn(new QuotaBillingPort.Reservation("freeze-3", 200_000L));
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider socket closed"));

        BillableModelInvocationService service = new BillableModelInvocationService(recorder, quota);
        assertThrows(IllegalStateException.class, () -> service.invoke(model, prompt("describe image"),
                new ModelInvocationPolicy(ModelInvocationPolicy.CostOwner.USER_QUOTA, 7L, 9L, "req-3",
                        "agent_loop", 2, "vlm_describe", "qwen-vl-plus", 800, 1024, 5L, 30L, 0D)));

        verify(quota).markProviderStarted("freeze-3");
        verify(quota, never()).releaseWithUsage(eq("freeze-3"), any());
        verify(quota, never()).settleWithUsage(eq("freeze-3"), anyLong(), any());
        ArgumentCaptor<LlmInvocationFinishRecord> finish = ArgumentCaptor.forClass(LlmInvocationFinishRecord.class);
        verify(recorder).finishLlmInvocation(finish.capture());
        org.junit.jupiter.api.Assertions.assertEquals("PROVIDER_OUTCOME_UNKNOWN", finish.getValue().getUsageSource());
    }

    private Prompt prompt(String text) {
        return new Prompt(List.of(new UserMessage(text)));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
