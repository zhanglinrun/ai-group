package com.linrun.agent.domain.agent.quota;

/** Member-side terminal action durably selected after provider execution. */
public enum QuotaSettlementIntent {
    NONE,
    CONFIRM,
    RELEASE
}
