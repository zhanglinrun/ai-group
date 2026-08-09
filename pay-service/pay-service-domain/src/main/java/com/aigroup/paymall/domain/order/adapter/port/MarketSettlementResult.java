package com.aigroup.paymall.domain.order.adapter.port;

/**
 * Result of registering a paid order with the group-buy service.
 */
public enum MarketSettlementResult {
    ACKNOWLEDGED,
    RETRYABLE_FAILURE,
    TERMINAL_REJECTED
}
