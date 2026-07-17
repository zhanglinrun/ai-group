package com.linrun.agent.domain.agent.ledger.model;

import java.time.LocalDateTime;

/** Compare-and-set command for terminalizing worker-lost dialogue runs. */
public record DialogueRunRecoveryCommand(
        LocalDateTime deadlineBefore,
        LocalDateTime heartbeatBefore,
        LocalDateTime finishedAt,
        Integer terminalStatus,
        String errorCode,
        String errorMsg,
        int batchLimit
) {
}
