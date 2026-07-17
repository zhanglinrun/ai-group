package com.linrun.agent.domain.agent.quota;

/** Prevents a duplicate caller from reusing an already consumed provider admission. */
public class QuotaProviderAlreadyStartedException extends QuotaSettlementConflictException {

    public QuotaProviderAlreadyStartedException(String message) {
        super(message);
    }
}
