package org.wwz.ai.application.agent.stream;

import lombok.RequiredArgsConstructor;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Confirms or releases quota when the downstream SSE stream terminates.
 */
@RequiredArgsConstructor
public class QuotaBillingAgentSessionStream implements AgentSessionStream {

    private final AgentSessionStream delegate;
    private final MemberQuotaBillingService memberQuotaBillingService;
    private final String freezeId;
    private final AtomicBoolean settled = new AtomicBoolean(false);

    @Override
    public void send(Object payload) throws Exception {
        delegate.send(payload);
    }

    @Override
    public void complete() {
        delegate.complete();
        settleSuccess();
    }

    /**
     * 正常关闭下游流，但按失败结算（释放冻结额度）。
     * 用于 agent 内部吞掉异常、SSE 已正常输出降级结果、而账本 run 终态为失败/停止的场景。
     */
    public void completeWithFailureSettlement() {
        try {
            delegate.complete();
        } finally {
            settleFailure();
        }
    }

    @Override
    public void completeWithError(Throwable throwable) {
        try {
            delegate.completeWithError(throwable);
        } finally {
            settleFailure();
        }
    }

    @Override
    public void onAbort(Runnable abortHandler) {
        delegate.onAbort(() -> {
            try {
                abortHandler.run();
            } finally {
                settleFailure();
            }
        });
    }

    @Override
    public boolean isAborted() {
        return delegate.isAborted();
    }

    private void settleSuccess() {
        if (settled.compareAndSet(false, true)) {
            memberQuotaBillingService.confirm(freezeId);
        }
    }

    private void settleFailure() {
        if (settled.compareAndSet(false, true)) {
            memberQuotaBillingService.release(freezeId);
        }
    }
}
