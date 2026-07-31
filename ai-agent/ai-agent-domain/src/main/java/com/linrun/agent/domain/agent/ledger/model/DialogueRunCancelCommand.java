package com.linrun.agent.domain.agent.ledger.model;

import java.time.LocalDateTime;

/** First-action-wins cancellation intent for an owner-scoped durable run. */
public record DialogueRunCancelCommand(Long runId,
                                       String ownerId,
                                       LocalDateTime requestedAt) {
}
