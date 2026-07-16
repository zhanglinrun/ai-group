package org.wwz.ai.domain.agent.adapter.port;

/** Per-LLM-call quota reservation boundary implemented by the member service adapter. */
public interface QuotaBillingPort {

    Reservation reserve(Long userId, long requestedMicrocredits, long minimumMicrocredits, String requestId);

    void settle(String freezeId, long actualMicrocredits);

    void release(String freezeId);

    record Reservation(String freezeId, long reservedMicrocredits) {
    }
}
