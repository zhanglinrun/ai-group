package org.wwz.ai.application.agent.quota;

/**
 * 配额不足时抛出。
 */
public class QuotaInsufficientException extends RuntimeException {

    public QuotaInsufficientException(String message) {
        super(message);
    }
}
