package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Run-scoped structured cancellation shared by the model loop, tools and child work.
 * A deadline, an explicit cancellation and a downstream disconnect all converge on
 * one typed stop reason.
 */
public final class CancellationToken {

    private final AtomicReference<AgentStopReason> explicitReason =
            new AtomicReference<>(AgentStopReason.NONE);
    private volatile Long deadlineNanos;
    private volatile BooleanSupplier downstreamAbortProbe = () -> false;

    public void activateDeadline(long maxDurationMillis) {
        long durationMillis = Math.max(1L, maxDurationMillis);
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis);
        deadlineNanos = saturatingAdd(System.nanoTime(), durationNanos);
        explicitReason.set(AgentStopReason.NONE);
    }

    public void bindDownstreamAbortProbe(BooleanSupplier probe) {
        downstreamAbortProbe = probe == null ? () -> false : probe;
    }

    public void cancel(AgentStopReason reason) {
        AgentStopReason effective = reason == null || reason == AgentStopReason.NONE
                ? AgentStopReason.EXECUTION_ERROR
                : reason;
        explicitReason.compareAndSet(AgentStopReason.NONE, effective);
    }

    public AgentStopReason cancellationReason() {
        AgentStopReason explicit = explicitReason.get();
        if (explicit != AgentStopReason.NONE) {
            return explicit;
        }
        if (isDownstreamAborted()) {
            return AgentStopReason.DOWNSTREAM_ABORTED;
        }
        if (isDeadlineExceeded()) {
            return AgentStopReason.TIME_BUDGET;
        }
        return AgentStopReason.NONE;
    }

    public boolean isCancellationRequested() {
        return cancellationReason() != AgentStopReason.NONE;
    }

    public boolean hasDeadline() {
        return deadlineNanos != null;
    }

    public boolean isDeadlineExceeded() {
        return hasDeadline() && remainingNanos() == 0L;
    }

    public boolean isDownstreamAborted() {
        try {
            return downstreamAbortProbe.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public Duration remainingDuration() {
        return Duration.ofNanos(remainingNanos());
    }

    public long remainingNanos() {
        Long deadline = deadlineNanos;
        if (deadline == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadline - System.nanoTime());
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
