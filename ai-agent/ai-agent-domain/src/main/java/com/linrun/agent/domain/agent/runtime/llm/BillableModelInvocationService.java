package com.linrun.agent.domain.agent.runtime.llm;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Shared durable ledger and quota boundary for model calls outside {@link LLM}.
 *
 * <p>Only {@code USER_QUOTA} invokes member quota operations. Platform-owned
 * work is still recorded in the same invocation ledger with its computed cost,
 * but never receives a member freeze id.</p>
 */
@Service
@RequiredArgsConstructor
public class BillableModelInvocationService {

    private final AgentExecutionRecorder executionRecorder;
    private final QuotaBillingPort quotaBillingPort;
    private final TokenCounter tokenCounter = new TokenCounter();

    public ChatResponse invoke(ChatModel chatModel, Prompt prompt, ModelInvocationPolicy policy) {
        if (chatModel == null || prompt == null || policy == null) {
            throw new IllegalArgumentException("chatModel, prompt and policy are required");
        }
        InvocationSnapshotFactory.InvocationSnapshot snapshot =
                InvocationSnapshotFactory.forDirectCall(prompt, policy);
        LocalDateTime startedAt = LocalDateTime.now();
        long invocationStartedNanos = System.nanoTime();
        Long invocationId = executionRecorder.createLlmInvocation(LlmInvocationStartRecord.builder()
                .runId(policy.runId())
                .requestId(policy.requestId())
                .agentName(policy.agentName())
                .stepNo(policy.stepNo())
                .callKind(policy.callKind())
                .streaming(false)
                .modelName(policy.modelName())
                .costOwner(policy.costOwner().name())
                .promptHash(snapshot.promptHash())
                .modelParametersJson(snapshot.modelParametersJson())
                .toolSnapshotJson(snapshot.toolSnapshotJson())
                .skillSnapshotJson(snapshot.skillSnapshotJson())
                .configHash(snapshot.configHash())
                .inputRateSnapshot(policy.inputRateSnapshot())
                .outputRateSnapshot(policy.outputRateSnapshot())
                .startedAt(startedAt)
                .build());
        if (invocationId == null) {
            throw new IllegalStateException("model invocation ledger insert failed before provider admission");
        }

        QuotaBillingPort.Reservation reservation = null;
        boolean providerStarted = false;
        try {
            if (policy.costOwner() == ModelInvocationPolicy.CostOwner.USER_QUOTA) {
                LlmQuotaCalculator.ReservationAmounts amounts = LlmQuotaCalculator.reservation(
                        policy.estimatedInputTokens(), policy.maxOutputTokens(),
                        policy.inputRateSnapshot(), policy.outputRateSnapshot());
                reservation = quotaBillingPort.reserve(policy.ownerId(), amounts.requestedMicrocredits(),
                        amounts.minimumMicrocredits(), policy.callKind(),
                        policy.requestId() + ":llm:" + invocationId);
                int affordableOutputTokens = LlmQuotaCalculator.affordableOutputTokens(
                        reservation.reservedMicrocredits(), policy.estimatedInputTokens(),
                        policy.maxOutputTokens(), policy.inputRateSnapshot(), policy.outputRateSnapshot());
                if (affordableOutputTokens < LlmQuotaCalculator.MIN_OUTPUT_TOKENS) {
                    quotaBillingPort.releaseWithUsage(reservation.freezeId(), emptyUsage(invocationId, policy));
                    reservation = null;
                    throw new IllegalStateException("额度不足，无法支持最少256个输出Token");
                }
                quotaBillingPort.markProviderStarted(reservation.freezeId());
                providerStarted = true;
            }

            long providerStartedNanos = System.nanoTime();
            ChatResponse response = chatModel.call(prompt);
            long latencyMs = elapsedMillis(providerStartedNanos);
            LlmUsageSettlement.Result usage = LlmUsageSettlement.resolve(
                    providerPromptTokens(response),
                    providerCompletionTokens(response),
                    policy.estimatedInputTokens(),
                    estimateOutputTokens(response),
                    policy.inputRateSnapshot(),
                    policy.outputRateSnapshot());
            if (reservation != null) {
                LlmQuotaSettlementExecutor.apply(quotaBillingPort, reservation, usage,
                        new QuotaBillingPort.UsageMetadata(
                                invocationId,
                                policy.inputRateSnapshot(),
                                policy.outputRateSnapshot(),
                                usage.inputTokens(),
                                usage.outputTokens(),
                                usage.usageSource(),
                                usage.chargedMicrocredits()));
            }
            executionRecorder.finishLlmInvocation(LlmInvocationFinishRecord.builder()
                    .llmInvocationId(invocationId)
                    .requestId(policy.requestId())
                    .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                    .responseText(responseText(response))
                    .toolCallCount(0)
                    .promptTokens(usage.inputTokens())
                    .completionTokens(usage.outputTokens())
                    .totalTokens(usage.inputTokens() + usage.outputTokens())
                    .usageSource(usage.usageSource())
                    .chargedMicrocredits(usage.chargedMicrocredits())
                    .providerLatencyMs(latencyMs)
                    .durationMs(elapsedMillis(invocationStartedNanos))
                    .finishedAt(LocalDateTime.now())
                    .build());
            return response;
        } catch (RuntimeException failure) {
            // Before admission no provider call exists, so a reserved freeze can be released.
            // Once admission was persisted, leave the fact intact for the durable coordinator's
            // state query/recovery path rather than guessing a terminal member-side action.
            if (reservation != null && !providerStarted) {
                try {
                    quotaBillingPort.releaseWithUsage(reservation.freezeId(), emptyUsage(invocationId, policy));
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            executionRecorder.finishLlmInvocation(LlmInvocationFinishRecord.builder()
                    .llmInvocationId(invocationId)
                    .requestId(policy.requestId())
                    .status(ExecutionLedgerConstants.resolveFailureStatus(failure))
                    .promptTokens(0)
                    .completionTokens(0)
                    .totalTokens(0)
                    .usageSource(providerStarted ? "PROVIDER_OUTCOME_UNKNOWN" : "UNAVAILABLE")
                    .chargedMicrocredits(0L)
                    .durationMs(elapsedMillis(invocationStartedNanos))
                    .errorMsg(failure.getMessage())
                    .finishedAt(LocalDateTime.now())
                    .build());
            throw failure;
        }
    }

    private QuotaBillingPort.UsageMetadata emptyUsage(Long invocationId, ModelInvocationPolicy policy) {
        return new QuotaBillingPort.UsageMetadata(
                invocationId, policy.inputRateSnapshot(), policy.outputRateSnapshot(),
                0, 0, "PROVIDER_NOT_STARTED", 0L);
    }

    private Integer providerPromptTokens(ChatResponse response) {
        Usage usage = metadata(response) == null ? null : metadata(response).getUsage();
        return usage == null ? null : usage.getPromptTokens();
    }

    private Integer providerCompletionTokens(ChatResponse response) {
        Usage usage = metadata(response) == null ? null : metadata(response).getUsage();
        return usage == null ? null : usage.getCompletionTokens();
    }

    private ChatResponseMetadata metadata(ChatResponse response) {
        return response == null ? null : response.getMetadata();
    }

    private int estimateOutputTokens(ChatResponse response) {
        return tokenCounter.countText(responseText(response));
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
