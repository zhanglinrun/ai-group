package com.aigroup.paymall.domain.order.adapter.port;

/**
 * Development-only orchestration port used after a real local group settlement was recorded.
 * The production application never calls this port because its HTTP entry point is not registered.
 */
public interface IDemoGroupPort {

    /**
     * Finalize the real group containing an already-paid member order.
     *
     * @return true when group confirmed the request (including an idempotent repeat)
     */
    boolean finalizePaidGroup(String userId, String outTradeNo);
}
