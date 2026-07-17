package com.linrun.agent.domain.agent.adapter.port;

/**
 * Raised when the quota billing boundary cannot reserve or settle enough quota.
 */
public class QuotaInsufficientException extends RuntimeException {

    public QuotaInsufficientException(String message) {
        super(message);
    }
}
