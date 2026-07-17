package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;

/**
 * Tool retry policy. Retries are bounded and require an explicit idempotency
 * opt-in from the selected tool.
 */
public final class RetryPolicy {

    private final int maxAttempts;

    public RetryPolicy(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(1);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean shouldRetry(BaseTool tool, int completedAttempt, AgentContext context) {
        if (tool == null || !tool.isRetryable() || completedAttempt >= maxAttempts) {
            return false;
        }
        return context == null || (!context.isRunDeadlineExceeded() && !context.isDownstreamAborted());
    }
}
