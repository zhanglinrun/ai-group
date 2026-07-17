package com.linrun.agent.domain.agent.quota;

/** Raised only when durable intent contradicts an already selected or remote terminal state. */
public class QuotaSettlementConflictException extends IllegalStateException {

    public QuotaSettlementConflictException(String message) {
        super(message);
    }
}
