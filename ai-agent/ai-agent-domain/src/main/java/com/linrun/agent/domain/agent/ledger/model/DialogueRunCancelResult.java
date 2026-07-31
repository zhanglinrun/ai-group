package com.linrun.agent.domain.agent.ledger.model;

/** Observable result of an idempotent owner-scoped cancellation request. */
public record DialogueRunCancelResult(Status status, Long runId, String requestId) {

    public enum Status {
        ACCEPTED,
        ALREADY_REQUESTED,
        TERMINAL,
        OWNER_MISMATCH,
        NOT_FOUND
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED || status == Status.ALREADY_REQUESTED;
    }
}
