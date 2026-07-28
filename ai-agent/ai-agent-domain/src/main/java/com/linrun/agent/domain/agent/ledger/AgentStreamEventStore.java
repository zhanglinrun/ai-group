package com.linrun.agent.domain.agent.ledger;

import java.util.List;

/** Canonical stream-event ledger used by live delivery and exact replay. */
public interface AgentStreamEventStore {

    void append(String requestId, String eventType, String eventJson);

    List<StoredStreamEvent> findByRequestId(String requestId);

    record StoredStreamEvent(long sequence, String eventType, String eventJson) {
    }
}
