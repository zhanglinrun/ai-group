package com.linrun.agent.domain.agent.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Lease/CAS recovery for ambiguous reserve responses and pending terminal actions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaSettlementRecoveryService {

    private final IQuotaSettlementCommandRepository repository;
    private final DurableQuotaBillingCoordinator coordinator;

    public int recoverDue(Duration leaseDuration, int batchLimit) {
        Duration effectiveLease = leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()
                ? Duration.ofSeconds(30) : leaseDuration;
        String leaseOwner = "quota-" + UUID.randomUUID().toString().replace("-", "");
        List<QuotaSettlementCommand> commands = repository.claimDue(
                leaseOwner,
                Math.max(1L, effectiveLease.toSeconds()),
                Math.max(1, batchLimit));
        int attempted = 0;
        for (QuotaSettlementCommand command : commands) {
            attempted++;
            try {
                coordinator.recoverClaim(command);
            } catch (Exception failure) {
                log.warn("quota settlement recovery failed commandId={} state={} errorType={}",
                        command == null ? null : command.getId(),
                        command == null ? null : command.getState(),
                        failure.getClass().getSimpleName());
            }
        }
        return attempted;
    }
}
