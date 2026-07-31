package com.linrun.agent.domain.agent.runtime.llm;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable accounting and provenance contract for a non-loop model invocation.
 *
 * <p>The Agent Loop owns its {@link AgentContext} directly. File ingestion is a
 * synchronous tool sub-flow, so this class carries that existing context through
 * the router without adding it to a persisted document request. The binding is
 * removed in a {@code finally} block and is never used by asynchronous memory
 * work.</p>
 */
public record ModelInvocationPolicy(
        CostOwner costOwner,
        Long ownerId,
        Long runId,
        String requestId,
        String agentName,
        Integer stepNo,
        String callKind,
        String modelName,
        int maxOutputTokens,
        int estimatedInputTokens,
        long inputRateSnapshot,
        long outputRateSnapshot,
        double temperature
) {

    public enum CostOwner {
        USER_QUOTA,
        PLATFORM_COST
    }

    private static final ThreadLocal<AgentContext> USER_INVOCATION_CONTEXT = new ThreadLocal<>();

    public ModelInvocationPolicy {
        Objects.requireNonNull(costOwner, "costOwner must not be null");
        if (runId == null || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("runId and requestId are required for a model invocation");
        }
        if (costOwner == CostOwner.USER_QUOTA && ownerId == null) {
            throw new IllegalArgumentException("user quota invocations require an ownerId");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is required for a model invocation");
        }
        if (maxOutputTokens <= 0 || estimatedInputTokens < 0
                || inputRateSnapshot <= 0 || outputRateSnapshot <= 0) {
            throw new IllegalArgumentException("model invocation token and rate snapshots must be positive");
        }
    }

    public static ModelInvocationPolicy userQuota(AgentContext context,
                                                   String callKind,
                                                   String modelName,
                                                   int maxOutputTokens,
                                                   int estimatedInputTokens,
                                                   long inputRateSnapshot,
                                                   long outputRateSnapshot,
                                                   double temperature) {
        if (context == null || !context.hasActiveLedgerRun() || context.getAgentRunState() == null) {
            throw new IllegalStateException("user model invocation requires an active durable agent run");
        }
        return new ModelInvocationPolicy(
                CostOwner.USER_QUOTA,
                context.getOwnerId(),
                context.getAgentRunState().getRunId(),
                context.getRequestId(),
                context.getAgentRunState().getCurrentAgentName(),
                context.getAgentRunState().getCurrentStepNo(),
                callKind,
                modelName,
                maxOutputTokens,
                estimatedInputTokens,
                inputRateSnapshot,
                outputRateSnapshot,
                temperature);
    }

    public static ModelInvocationPolicy platformCost(Long runId,
                                                     String requestId,
                                                     String callKind,
                                                     String modelName,
                                                     int maxOutputTokens,
                                                     int estimatedInputTokens,
                                                     long inputRateSnapshot,
                                                     long outputRateSnapshot,
                                                     double temperature) {
        return new ModelInvocationPolicy(
                CostOwner.PLATFORM_COST,
                null,
                runId,
                requestId,
                "memory_summary",
                null,
                callKind,
                modelName,
                maxOutputTokens,
                estimatedInputTokens,
                inputRateSnapshot,
                outputRateSnapshot,
                temperature);
    }

    public static <T> T withinUserInvocation(AgentContext context, Supplier<T> supplier) {
        AgentContext previous = USER_INVOCATION_CONTEXT.get();
        USER_INVOCATION_CONTEXT.set(context);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                USER_INVOCATION_CONTEXT.remove();
            } else {
                USER_INVOCATION_CONTEXT.set(previous);
            }
        }
    }

    public static AgentContext requireCurrentUserInvocationContext() {
        AgentContext context = USER_INVOCATION_CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("user VLM invocation is outside the authenticated Agent execution context");
        }
        return context;
    }
}
