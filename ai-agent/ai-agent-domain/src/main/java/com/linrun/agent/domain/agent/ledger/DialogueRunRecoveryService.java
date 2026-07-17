package com.linrun.agent.domain.agent.ledger;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunRecoveryCommand;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;

import java.time.Duration;
import java.time.LocalDateTime;

/** Terminalizes orphaned runs without taking over or replaying model/tool work. */
@Service
@RequiredArgsConstructor
public class DialogueRunRecoveryService {

    public static final String WORKER_LOST_ERROR_CODE = "RUN_WORKER_LOST";
    private static final String WORKER_LOST_ERROR_MESSAGE =
            "Agent worker heartbeat expired after the durable run deadline; automatic replay is disabled.";

    private final IExecutionLedgerWriteRepository repository;

    public int failWorkerLostRuns(LocalDateTime now,
                                  Duration deadlineGrace,
                                  Duration heartbeatTimeout,
                                  int batchLimit) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        Duration effectiveGrace = nonNegative(deadlineGrace);
        Duration effectiveHeartbeatTimeout = positive(heartbeatTimeout, Duration.ofMinutes(1));
        return repository.failWorkerLostRuns(new DialogueRunRecoveryCommand(
                effectiveNow.minus(effectiveGrace),
                effectiveNow.minus(effectiveHeartbeatTimeout),
                effectiveNow,
                ExecutionLedgerConstants.STATUS_FAILED,
                WORKER_LOST_ERROR_CODE,
                WORKER_LOST_ERROR_MESSAGE,
                Math.max(1, batchLimit)
        ));
    }

    private Duration nonNegative(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
