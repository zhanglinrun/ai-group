package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将模型和长耗时工具等待统一限制在“调用自身上限”和“run 剩余预算”的较小值内。
 * 短轮询只用于及时感知下游断开，不会创建额外线程。
 */
public final class AgentFutureWaiter {

    private static final long ABORT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);

    private AgentFutureWaiter() {
    }

    public static <T> T await(Future<T> future,
                              AgentContext context,
                              Duration callLimit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(future, "future must not be null");
        Objects.requireNonNull(callLimit, "callLimit must not be null");
        long callLimitNanos = positiveNanos(callLimit);
        long callDeadlineNanos = System.nanoTime() + callLimitNanos;

        while (true) {
            if (context != null) {
                AgentStopReason cancellationReason = context.cancellationReason();
                if (cancellationReason == AgentStopReason.DOWNSTREAM_ABORTED) {
                    future.cancel(true);
                    throw new DownstreamAbortedException("downstream disconnected while waiting");
                }
                if (cancellationReason == AgentStopReason.TIME_BUDGET) {
                    future.cancel(true);
                    throw new RunDeadlineExceededException("agent run time budget exhausted");
                }
                if (cancellationReason != AgentStopReason.NONE) {
                    future.cancel(true);
                    throw new RunCancelledException(cancellationReason);
                }
            }

            long runRemainingNanos = context == null
                    ? Long.MAX_VALUE
                    : context.remainingRunNanos();
            if (runRemainingNanos == 0L) {
                future.cancel(true);
                throw new RunDeadlineExceededException("agent run time budget exhausted");
            }

            long callRemainingNanos = Math.max(0L, callDeadlineNanos - System.nanoTime());
            if (callRemainingNanos == 0L) {
                future.cancel(true);
                throw new TimeoutException("call time limit exhausted");
            }

            long waitNanos = Math.min(Math.min(callRemainingNanos, runRemainingNanos), ABORT_POLL_NANOS);
            try {
                return future.get(Math.max(1L, waitNanos), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interruptedException) {
                future.cancel(true);
                throw interruptedException;
            } catch (TimeoutException ignoredPollTimeout) {
                // Re-evaluate both deadlines and downstream state on the next poll.
            }
        }
    }

    private static long positiveNanos(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return 1L;
        }
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static final class RunDeadlineExceededException extends TimeoutException {
        public RunDeadlineExceededException(String message) {
            super(message);
        }
    }

    public static final class DownstreamAbortedException extends CancellationException {
        public DownstreamAbortedException(String message) {
            super(message);
        }
    }

    public static final class RunCancelledException extends CancellationException {
        private final AgentStopReason stopReason;

        public RunCancelledException(AgentStopReason stopReason) {
            super("agent run cancelled: " + stopReason);
            this.stopReason = stopReason;
        }

        public AgentStopReason getStopReason() {
            return stopReason;
        }
    }
}
