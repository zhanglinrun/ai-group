package com.linrun.agent.domain.agent.quota;

/** Fail-closed signal when the agent cannot durably record a billing transition. */
public class QuotaSettlementPersistenceException extends IllegalStateException {

    public QuotaSettlementPersistenceException(String message) {
        super(message);
    }

    public QuotaSettlementPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
