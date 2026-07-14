package org.wwz.ai.domain.agent.runtime.evaluation;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run-scoped budget shared by evaluation and targeted replanning.
 */
public final class PlanReflectionBudget {

    private final int limit;
    private final AtomicInteger used = new AtomicInteger();

    public PlanReflectionBudget(int limit) {
        this.limit = Math.max(0, limit);
    }

    public boolean tryConsume(int tokens) {
        int normalized = Math.max(0, tokens);
        while (true) {
            int current = used.get();
            if ((long) current + normalized > limit) {
                return false;
            }
            if (used.compareAndSet(current, current + normalized)) {
                return true;
            }
        }
    }

    public int used() {
        return used.get();
    }

    public int remaining() {
        return Math.max(0, limit - used());
    }

    public int limit() {
        return limit;
    }
}
