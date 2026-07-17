package com.linrun.agent.domain.agent.quota;

/** Durable lifecycle of one billable provider invocation. */
public enum QuotaSettlementState {
    RESERVE_PENDING,
    RESERVED,
    /** Provider invocation was admitted; a crash now makes its business outcome unknowable. */
    PROVIDER_STARTED,
    APPLY_PENDING,
    CONFIRMED,
    RELEASED,
    RESERVE_FAILED,
    /** Managed freeze is intentionally preserved for operator/provider reconciliation. */
    MANUAL_REVIEW,
    CONFLICT;

    public boolean terminal() {
        return this == CONFIRMED || this == RELEASED || this == RESERVE_FAILED
                || this == MANUAL_REVIEW || this == CONFLICT;
    }
}
