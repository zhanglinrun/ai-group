package com.linrun.agent.domain.agent.ledger;

import java.util.List;

/** Canonical stream-event ledger used by live delivery and exact replay. */
public interface AgentStreamEventStore {

    void append(String requestId, String eventType, String eventJson);

    /** Persists one event and returns its durable sequence where supported. */
    default long appendAndGetSequence(String requestId, String eventType, String eventJson) {
        append(requestId, eventType, eventJson);
        return -1L;
    }

    List<StoredStreamEvent> findByRequestId(String requestId);

    default List<StoredStreamEvent> findByRequestIdAfter(String requestId, long afterSequence) {
        return findByRequestId(requestId).stream()
                .filter(event -> event.sequence() > Math.max(0L, afterSequence))
                .toList();
    }

    /** First retained per-run sequence, or {@code 0} when this run has no retained events. */
    default long earliestSequence(String requestId) {
        return findByRequestId(requestId).stream()
                .mapToLong(StoredStreamEvent::sequence)
                .min()
                .orElse(0L);
    }

    /** Latest durable per-run sequence, or {@code 0} when this run has no retained events. */
    default long latestSequence(String requestId) {
        return findByRequestId(requestId).stream()
                .mapToLong(StoredStreamEvent::sequence)
                .max()
                .orElse(0L);
    }

    record StoredStreamEvent(long sequence, String eventType, String eventJson) {
    }
}
