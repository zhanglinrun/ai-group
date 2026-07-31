package com.linrun.agent.domain.agent.ledger.model;

import java.time.LocalDateTime;

/** CAS payload for renewing one worker-owned durable run lease. */
public record DialogueRunLeaseRenewalCommand(Long runId,
                                             String requestId,
                                             String ownerWorkerId,
                                             long fencingToken,
                                             LocalDateTime heartbeatAt,
                                             LocalDateTime leaseExpiresAt) {
}
