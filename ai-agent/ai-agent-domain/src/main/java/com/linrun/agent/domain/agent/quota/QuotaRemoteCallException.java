package com.linrun.agent.domain.agent.quota;

/** Ambiguous member-service response that must be reconciled through the durable command. */
public class QuotaRemoteCallException extends RuntimeException {

    public QuotaRemoteCallException(String message) {
        super(message);
    }
}
