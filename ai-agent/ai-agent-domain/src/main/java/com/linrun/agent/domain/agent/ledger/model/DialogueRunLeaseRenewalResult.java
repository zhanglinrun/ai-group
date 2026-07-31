package com.linrun.agent.domain.agent.ledger.model;

/** Result of a durable lease heartbeat; callers must stop new side effects unless ACTIVE. */
public record DialogueRunLeaseRenewalResult(Status status) {

    public enum Status {
        ACTIVE,
        CANCEL_REQUESTED,
        OWNERSHIP_LOST,
        TERMINAL,
        NOT_FOUND
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
