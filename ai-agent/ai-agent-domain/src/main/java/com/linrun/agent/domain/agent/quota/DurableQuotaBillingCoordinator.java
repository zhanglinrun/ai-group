package com.linrun.agent.domain.agent.quota;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.quota.QuotaSettlementRemotePort.RemoteReservationStatus;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

/** Durable, fail-closed quota sequencing shared by LLM and billable tool providers. */
@Service
@RequiredArgsConstructor
public class DurableQuotaBillingCoordinator implements QuotaBillingPort {

    private static final String OWNER_SERVICE = "ai-agent";
    private static final int ERROR_MAX_LENGTH = 1000;
    private static final int MAX_RECOVERY_RETRIES = 10;
    private static final int TERMINAL_PERSIST_ATTEMPTS = 2;
    private static final long PROVIDER_OUTCOME_REVIEW_MINUTES = 30L;

    private final IQuotaSettlementCommandRepository repository;
    private final QuotaSettlementRemotePort remotePort;

    @Override
    public Reservation reserve(Long userId,
                               long requestedMicrocredits,
                               long minimumMicrocredits,
                               String requestId) {
        return reserve(userId, requestedMicrocredits, minimumMicrocredits, "llm_call", requestId);
    }

    @Override
    public Reservation reserve(Long userId,
                               long requestedMicrocredits,
                               long minimumMicrocredits,
                               String abilityCode,
                               String requestId) {
        return reserve(userId, requestedMicrocredits, minimumMicrocredits, abilityCode, requestId, null);
    }

    @Override
    public Reservation reserve(Long userId,
                               long requestedMicrocredits,
                               long minimumMicrocredits,
                               String abilityCode,
                               String requestId,
                               String traceId) {
        validateReserve(userId, requestedMicrocredits, minimumMicrocredits, abilityCode);
        String billingRequestId = QuotaBillingRequestId.normalize(requestId);
        String normalizedTraceId = normalizeTraceId(traceId);
        String normalizedAbility = abilityCode.trim().toLowerCase(Locale.ROOT);
        String fingerprint = QuotaBillingRequestId.fingerprint(
                userId, normalizedAbility, requestedMicrocredits, minimumMicrocredits);
        QuotaSettlementCommand proposed = QuotaSettlementCommand.builder()
                .userId(userId)
                .billingRequestId(billingRequestId)
                .traceId(normalizedTraceId)
                .requestFingerprint(fingerprint)
                .abilityCode(normalizedAbility)
                .requestedMicrocredits(requestedMicrocredits)
                .minimumMicrocredits(minimumMicrocredits)
                .reservedMicrocredits(0L)
                .intendedAction(QuotaSettlementIntent.NONE)
                .intendedMicrocredits(0L)
                .settledMicrocredits(0L)
                .chargedMicrocredits(0L)
                .state(QuotaSettlementState.RESERVE_PENDING)
                .retryCount(0)
                .version(0)
                .build();
        try {
            repository.insertIfAbsent(proposed);
        } catch (RuntimeException persistenceFailure) {
            throw new QuotaSettlementPersistenceException(
                    "failed to persist quota reserve intent before provider invocation", persistenceFailure);
        }

        QuotaSettlementCommand command = requireByRequest(userId, billingRequestId);
        QuotaSettlementDecision.ReserveAction action =
                QuotaSettlementDecision.decideReserve(command, fingerprint);
        if (action == QuotaSettlementDecision.ReserveAction.RETURN_RESERVED) {
            return toReservation(command);
        }
        if (action == QuotaSettlementDecision.ReserveAction.CONFLICT) {
            throw conflict("billingRequestId already belongs to another or admitted invocation", command, null);
        }
        return invokeReserve(command);
    }

    @Override
    public void markProviderStarted(String freezeId) {
        QuotaSettlementCommand command = requireByFreezeId(freezeId);
        if (command.getState() == QuotaSettlementState.PROVIDER_STARTED) {
            throw new QuotaProviderAlreadyStartedException(
                    "provider admission was already consumed, freezeId=" + freezeId);
        }
        if (command.getState() != QuotaSettlementState.RESERVED) {
            throw conflict("provider cannot start from quota state " + command.getState(), command, null);
        }
        if (repository.markProviderStarted(
                command.getId(), version(command), PROVIDER_OUTCOME_REVIEW_MINUTES)) {
            return;
        }
        QuotaSettlementCommand current = requireByFreezeId(freezeId);
        if (current.getState() == QuotaSettlementState.PROVIDER_STARTED) {
            throw new QuotaProviderAlreadyStartedException(
                    "provider admission was concurrently consumed, freezeId=" + freezeId);
        }
        throw new QuotaSettlementPersistenceException(
                "failed to persist PROVIDER_STARTED before provider invocation, freezeId=" + freezeId);
    }

    @Override
    public void settle(String freezeId, long actualMicrocredits) {
        settleWithUsage(freezeId, actualMicrocredits,
                new UsageMetadata(null, null, null, null, null, "FIXED", actualMicrocredits));
    }

    @Override
    public SettlementResult settleWithStatus(String freezeId, long actualMicrocredits) {
        return settleWithUsage(freezeId, actualMicrocredits,
                new UsageMetadata(null, null, null, null, null, "FIXED", actualMicrocredits));
    }

    @Override
    public SettlementResult settleWithUsage(String freezeId,
                                            long actualMicrocredits,
                                            UsageMetadata usageMetadata) {
        if (StringUtils.isBlank(freezeId)) {
            return new SettlementResult(freezeId, ReservationState.NOT_FOUND, 0L);
        }
        return applyIntent(freezeId, QuotaSettlementIntent.CONFIRM, actualMicrocredits, usageMetadata);
    }

    @Override
    public void release(String freezeId) {
        releaseWithUsage(freezeId,
                new UsageMetadata(null, null, null, null, null, "NOT_BILLABLE", 0L));
    }

    @Override
    public SettlementResult releaseWithStatus(String freezeId) {
        return releaseWithUsage(freezeId,
                new UsageMetadata(null, null, null, null, null, "NOT_BILLABLE", 0L));
    }

    @Override
    public SettlementResult releaseWithUsage(String freezeId, UsageMetadata usageMetadata) {
        if (StringUtils.isBlank(freezeId)) {
            return new SettlementResult(freezeId, ReservationState.NOT_FOUND, 0L);
        }
        return applyIntent(freezeId, QuotaSettlementIntent.RELEASE, 0L, usageMetadata);
    }

    @Override
    public ReservationStatus findByRequest(Long userId, String requestId) {
        String billingRequestId = QuotaBillingRequestId.normalize(requestId);
        QuotaSettlementCommand command = repository.findByUserAndBillingRequestId(userId, billingRequestId);
        return command == null
                ? new ReservationStatus(null, userId, 0L, 0L, ReservationState.NOT_FOUND, billingRequestId)
                : toReservationStatus(command);
    }

    @Override
    public ReservationStatus findByFreezeId(String freezeId) {
        QuotaSettlementCommand command = repository.findByFreezeId(freezeId);
        return command == null
                ? new ReservationStatus(freezeId, null, 0L, 0L, ReservationState.NOT_FOUND, null)
                : toReservationStatus(command);
    }

    /** Process one lease-claimed command. No provider work is ever replayed. */
    void recoverClaim(QuotaSettlementCommand command) {
        if (command == null || command.getState() == null) {
            throw new QuotaSettlementPersistenceException("claimed quota command is incomplete");
        }
        switch (command.getState()) {
            case RESERVE_PENDING -> recoverReserveClaim(command);
            case RESERVED -> recoverReservedBeforeProviderClaim(command);
            case PROVIDER_STARTED -> markManualReview(command,
                    "provider outcome is unknown after worker loss; managed freeze preserved");
            case APPLY_PENDING -> recoverApplyClaim(command);
            default -> {
                // A concurrent owner already moved the command; stale claims do no work.
            }
        }
    }

    private void recoverReservedBeforeProviderClaim(QuotaSettlementCommand command) {
        QuotaSettlementCommand pendingRelease = persistIntent(
                command,
                QuotaSettlementIntent.RELEASE,
                0L,
                new UsageMetadata(null, null, null, 0, 0, "PROVIDER_NOT_STARTED", 0L));
        invokeTerminalApply(pendingRelease);
    }

    private Reservation invokeReserve(QuotaSettlementCommand command) {
        try {
            RemoteReservationStatus status = remotePort.reserveRemote(
                    command.getUserId(),
                    value(command.getRequestedMicrocredits()),
                    value(command.getMinimumMicrocredits()),
                    command.getAbilityCode(),
                    command.getBillingRequestId(),
                    command.getTraceId());
            validateRemoteSnapshot(command, status, false);
            if (status.state() != ReservationState.PENDING) {
                throw conflict("member reserve returned terminal state " + status.state(), command, null);
            }
            return persistReserved(command, status);
        } catch (QuotaSettlementConflictException | QuotaSettlementPersistenceException decisiveFailure) {
            throw decisiveFailure;
        } catch (RuntimeException ambiguousFailure) {
            return recoverAmbiguousReserve(command, ambiguousFailure);
        }
    }

    private Reservation recoverAmbiguousReserve(QuotaSettlementCommand command,
                                                 RuntimeException originalFailure) {
        try {
            RemoteReservationStatus status = remotePort.findByRequestRemote(
                    command.getUserId(), command.getBillingRequestId(), command.getTraceId());
            validateRemoteSnapshot(command, status, true);
            if (status.state() == ReservationState.PENDING) {
                return persistReserved(command, status);
            }
            if (status.state() != ReservationState.NOT_FOUND) {
                throw conflict("reserve lookup returned terminal member state " + status.state(),
                        command, originalFailure);
            }
            if (originalFailure instanceof QuotaInsufficientException rejected) {
                markReserveFailed(command, rejected);
                throw rejected;
            }
            scheduleOrManualReview(command, originalFailure);
            throw originalFailure;
        } catch (QuotaSettlementConflictException | QuotaSettlementPersistenceException decisiveFailure) {
            throw decisiveFailure;
        } catch (RuntimeException lookupFailure) {
            if (lookupFailure == originalFailure || lookupFailure instanceof QuotaInsufficientException) {
                throw lookupFailure;
            }
            originalFailure.addSuppressed(lookupFailure);
            scheduleOrManualReview(command, originalFailure);
            throw originalFailure;
        }
    }

    private Reservation persistReserved(QuotaSettlementCommand command, RemoteReservationStatus status) {
        if (repository.markReserved(command.getId(), version(command),
                status.freezeId(), status.reservedMicrocredits())) {
            return new Reservation(status.freezeId(), status.reservedMicrocredits());
        }
        QuotaSettlementCommand current = requireByRequest(command.getUserId(), command.getBillingRequestId());
        if (current.getState() == QuotaSettlementState.RESERVED
                && Objects.equals(current.getFreezeId(), status.freezeId())
                && Objects.equals(current.getReservedMicrocredits(), status.reservedMicrocredits())) {
            return toReservation(current);
        }
        throw new QuotaSettlementPersistenceException(
                "quota reserve result compare-and-set failed, billingRequestId=" + command.getBillingRequestId());
    }

    private void markReserveFailed(QuotaSettlementCommand command, RuntimeException failure) {
        if (repository.markReserveFailed(command.getId(), version(command), error(failure))) {
            return;
        }
        QuotaSettlementCommand current = requireByRequest(command.getUserId(), command.getBillingRequestId());
        if (current.getState() != QuotaSettlementState.RESERVE_FAILED) {
            throw new QuotaSettlementPersistenceException(
                    "failed to persist rejected quota reserve command", failure);
        }
    }

    private SettlementResult applyIntent(String freezeId,
                                         QuotaSettlementIntent intent,
                                         long actualMicrocredits,
                                         UsageMetadata usageMetadata) {
        QuotaSettlementCommand command = requireByFreezeId(freezeId);
        validateApply(command, intent, actualMicrocredits, usageMetadata);
        QuotaSettlementDecision.ApplyAction action =
                QuotaSettlementDecision.decideApply(command, intent, actualMicrocredits);
        if (action == QuotaSettlementDecision.ApplyAction.RETURN_TERMINAL) {
            return toSettlementResult(command);
        }
        if (action == QuotaSettlementDecision.ApplyAction.CONFLICT) {
            throw conflict("quota terminal intent conflicts with durable command", command, null);
        }
        if (action == QuotaSettlementDecision.ApplyAction.PERSIST_INTENT) {
            command = persistIntent(command, intent, actualMicrocredits, usageMetadata);
        }
        return invokeTerminalApply(command);
    }

    private QuotaSettlementCommand persistIntent(QuotaSettlementCommand command,
                                                 QuotaSettlementIntent intent,
                                                 long actualMicrocredits,
                                                 UsageMetadata usage) {
        if (repository.persistIntent(
                command.getId(), version(command), intent, actualMicrocredits,
                usage == null ? null : usage.llmInvocationId(),
                usage == null ? null : usage.inputRateSnapshot(),
                usage == null ? null : usage.outputRateSnapshot(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.usageSource(),
                usage == null ? actualMicrocredits : usage.chargedMicrocredits())) {
            return requireByFreezeId(command.getFreezeId());
        }
        QuotaSettlementCommand current = requireByFreezeId(command.getFreezeId());
        QuotaSettlementDecision.ApplyAction currentAction =
                QuotaSettlementDecision.decideApply(current, intent, actualMicrocredits);
        if (currentAction == QuotaSettlementDecision.ApplyAction.INVOKE_REMOTE
                || currentAction == QuotaSettlementDecision.ApplyAction.RETURN_TERMINAL) {
            return current;
        }
        if (currentAction == QuotaSettlementDecision.ApplyAction.CONFLICT) {
            throw conflict("concurrent quota terminal intent conflict", current, null);
        }
        throw new QuotaSettlementPersistenceException(
                "failed to durably persist quota terminal intent, freezeId=" + command.getFreezeId());
    }

    private SettlementResult invokeTerminalApply(QuotaSettlementCommand command) {
        try {
            RemoteReservationStatus status = command.getIntendedAction() == QuotaSettlementIntent.CONFIRM
                    ? remotePort.confirmRemote(command.getFreezeId(), value(command.getIntendedMicrocredits()),
                    command.getBillingRequestId(), command.getTraceId())
                    : remotePort.releaseRemote(command.getFreezeId(), command.getBillingRequestId(),
                    command.getTraceId());
            validateRemoteSnapshot(command, status, false);
            return convergeRemoteStatus(command, status, null);
        } catch (QuotaSettlementConflictException | QuotaSettlementPersistenceException decisiveFailure) {
            throw decisiveFailure;
        } catch (RuntimeException remoteFailure) {
            try {
                RemoteReservationStatus status = remotePort.findByFreezeIdRemote(command.getFreezeId(),
                        command.getBillingRequestId(), command.getTraceId());
                validateRemoteSnapshot(command, status, true);
                return convergeRemoteStatus(command, status, remoteFailure);
            } catch (QuotaSettlementConflictException | QuotaSettlementPersistenceException decisiveFailure) {
                throw decisiveFailure;
            } catch (RuntimeException queryFailure) {
                remoteFailure.addSuppressed(queryFailure);
                return acceptPending(command, remoteFailure);
            }
        }
    }

    private SettlementResult convergeRemoteStatus(QuotaSettlementCommand command,
                                                   RemoteReservationStatus status,
                                                   RuntimeException precedingFailure) {
        if (status.state() == ReservationState.PENDING
                || status.state() == ReservationState.NOT_FOUND
                || status.state() == ReservationState.UNKNOWN) {
            return acceptPending(command, precedingFailure);
        }
        ReservationState expected = command.getIntendedAction() == QuotaSettlementIntent.CONFIRM
                ? ReservationState.CONFIRMED : ReservationState.RELEASED;
        if (status.state() != expected) {
            throw conflict("member terminal state conflicts with durable intent: " + status.state(),
                    command, precedingFailure);
        }
        long expectedAmount = command.getIntendedAction() == QuotaSettlementIntent.CONFIRM
                ? value(command.getIntendedMicrocredits()) : 0L;
        if (expected == ReservationState.CONFIRMED && status.settledMicrocredits() != expectedAmount) {
            throw conflict("member confirmed a different quota amount", command, precedingFailure);
        }
        return persistTerminal(command, expected, status.settledMicrocredits());
    }

    private SettlementResult persistTerminal(QuotaSettlementCommand command,
                                              ReservationState remoteState,
                                              long settledMicrocredits) {
        QuotaSettlementState terminalState = remoteState == ReservationState.CONFIRMED
                ? QuotaSettlementState.CONFIRMED : QuotaSettlementState.RELEASED;
        for (int attempt = 0; attempt < TERMINAL_PERSIST_ATTEMPTS; attempt++) {
            if (repository.markTerminal(command.getId(), version(command), terminalState, settledMicrocredits)) {
                return new SettlementResult(command.getFreezeId(), remoteState, settledMicrocredits);
            }
            QuotaSettlementCommand current = requireByFreezeId(command.getFreezeId());
            if (current.getState() == terminalState
                    && (terminalState == QuotaSettlementState.RELEASED
                    || Objects.equals(current.getSettledMicrocredits(), settledMicrocredits))) {
                return toSettlementResult(current);
            }
            if (current.getState().terminal()) {
                throw conflict("concurrent quota terminal state conflict", current, null);
            }
            if (current.getState() == QuotaSettlementState.APPLY_PENDING
                    && sameTerminalIntent(current, terminalState, settledMicrocredits)) {
                // A recovery lease can advance only the local version while the remote terminal effect completes.
                command = current;
                continue;
            }
            throw new QuotaSettlementPersistenceException(
                    "member terminal state was applied but local command could not be finalized, freezeId="
                            + command.getFreezeId());
        }
        throw new QuotaSettlementPersistenceException(
                "member terminal state was applied but local command could not be finalized, freezeId="
                        + command.getFreezeId());
    }

    private boolean sameTerminalIntent(QuotaSettlementCommand command,
                                       QuotaSettlementState terminalState,
                                       long settledMicrocredits) {
        return (terminalState == QuotaSettlementState.CONFIRMED
                && command.getIntendedAction() == QuotaSettlementIntent.CONFIRM
                && Objects.equals(command.getIntendedMicrocredits(), settledMicrocredits))
                || (terminalState == QuotaSettlementState.RELEASED
                && command.getIntendedAction() == QuotaSettlementIntent.RELEASE);
    }

    /** A transient remote failure is a durable acceptance once APPLY_PENDING exists. */
    private SettlementResult acceptPending(QuotaSettlementCommand command, RuntimeException failure) {
        scheduleOrManualReview(command, failure);
        QuotaSettlementCommand current = requireByFreezeId(command.getFreezeId());
        if (current.getState() == QuotaSettlementState.CONFIRMED
                || current.getState() == QuotaSettlementState.RELEASED) {
            return toSettlementResult(current);
        }
        return new SettlementResult(command.getFreezeId(), ReservationState.PENDING, 0L);
    }

    private void recoverReserveClaim(QuotaSettlementCommand command) {
        RemoteReservationStatus status;
        try {
            status = remotePort.findByRequestRemote(
                    command.getUserId(), command.getBillingRequestId(), command.getTraceId());
            validateRemoteSnapshot(command, status, true);
        } catch (QuotaSettlementConflictException | QuotaSettlementPersistenceException decisiveFailure) {
            throw decisiveFailure;
        } catch (RuntimeException transientFailure) {
            if (retryCount(command) >= MAX_RECOVERY_RETRIES) {
                markManualReview(command, "reserve query failed after maximum recovery attempts");
            } else {
                scheduleOrManualReview(command, transientFailure);
            }
            return;
        }
        if (status.state() == ReservationState.PENDING) {
            persistReserved(command, status);
            return;
        }
        if (status.state() != ReservationState.NOT_FOUND) {
            throw conflict("reserve recovery observed terminal state " + status.state(), command, null);
        }
        if (retryCount(command) >= MAX_RECOVERY_RETRIES) {
            markManualReview(command, "reserve remained absent after maximum recovery attempts");
            return;
        }
        // The process may have crashed after persisting RESERVE_PENDING but before the
        // member request left this service. Reissuing the same immutable billingRequestId
        // is safe because member owns the idempotency key and payload fingerprint.
        // invokeReserve owns ambiguous-response lookup and retry scheduling; keeping it
        // outside the query catch prevents the same failure from being counted twice.
        invokeReserve(command);
    }

    private void recoverApplyClaim(QuotaSettlementCommand command) {
        try {
            RemoteReservationStatus status = remotePort.findByFreezeIdRemote(command.getFreezeId(),
                    command.getBillingRequestId(), command.getTraceId());
            validateRemoteSnapshot(command, status, true);
            if (status.state() == ReservationState.CONFIRMED
                    || status.state() == ReservationState.RELEASED) {
                convergeRemoteStatus(command, status, null);
                return;
            }
            if (retryCount(command) >= MAX_RECOVERY_RETRIES) {
                markManualReview(command, "terminal settlement remained ambiguous after maximum recovery attempts");
                return;
            }
        } catch (QuotaSettlementConflictException | QuotaSettlementPersistenceException decisiveFailure) {
            throw decisiveFailure;
        } catch (RuntimeException queryFailure) {
            if (retryCount(command) >= MAX_RECOVERY_RETRIES) {
                markManualReview(command, "terminal settlement query failed after maximum recovery attempts");
                return;
            }
        }
        invokeTerminalApply(command);
    }

    private void scheduleOrManualReview(QuotaSettlementCommand command, RuntimeException failure) {
        if (retryCount(command) >= MAX_RECOVERY_RETRIES) {
            markManualReview(command, error(failure));
            return;
        }
        if (repository.scheduleRetry(command.getId(), version(command), retryDelaySeconds(command), error(failure))) {
            return;
        }
        QuotaSettlementCommand current = command.getFreezeId() == null
                ? requireByRequest(command.getUserId(), command.getBillingRequestId())
                : requireByFreezeId(command.getFreezeId());
        if (current.getVersion() != null && current.getVersion() > version(command)) {
            return;
        }
        throw new QuotaSettlementPersistenceException(
                "failed to schedule quota command recovery, commandId=" + command.getId(), failure);
    }

    private void markManualReview(QuotaSettlementCommand command, String reason) {
        if (repository.markManualReview(command.getId(), version(command), error(reason))) {
            return;
        }
        QuotaSettlementCommand current = command.getFreezeId() == null
                ? requireByRequest(command.getUserId(), command.getBillingRequestId())
                : requireByFreezeId(command.getFreezeId());
        if (current.getState() == QuotaSettlementState.MANUAL_REVIEW || current.getState().terminal()) {
            return;
        }
        throw new QuotaSettlementPersistenceException(
                "stale worker failed MANUAL_REVIEW version CAS, commandId=" + command.getId());
    }

    private void validateRemoteSnapshot(QuotaSettlementCommand command,
                                        RemoteReservationStatus status,
                                        boolean allowNotFound) {
        if (status == null) {
            throw new QuotaRemoteCallException("member quota query returned null instead of explicit NOT_FOUND");
        }
        if (status.state() == ReservationState.NOT_FOUND && allowNotFound) {
            return;
        }
        if (status.state() == ReservationState.NOT_FOUND) {
            throw new QuotaRemoteCallException("member quota mutation returned NOT_FOUND");
        }
        boolean matches = StringUtils.isNotBlank(status.freezeId())
                && Objects.equals(status.userId(), command.getUserId())
                && Objects.equals(status.requestedMicrocredits(), command.getRequestedMicrocredits())
                && Objects.equals(status.minimumMicrocredits(), command.getMinimumMicrocredits())
                && Objects.equals(status.abilityCode(), command.getAbilityCode())
                && Objects.equals(status.billingRequestId(), command.getBillingRequestId())
                && Objects.equals(status.requestFingerprint(), command.getRequestFingerprint())
                && OWNER_SERVICE.equals(normalizeOwner(status.ownerService()))
                && status.reservedMicrocredits() >= value(command.getMinimumMicrocredits())
                && status.reservedMicrocredits() <= value(command.getRequestedMicrocredits())
                && (command.getFreezeId() == null || Objects.equals(command.getFreezeId(), status.freezeId()));
        if (!matches) {
            throw conflict("member quota snapshot does not match durable reserve payload", command, null);
        }
    }

    private QuotaSettlementConflictException conflict(String message,
                                                       QuotaSettlementCommand command,
                                                       RuntimeException cause) {
        String detail = message + ", commandId=" + (command == null ? null : command.getId());
        if (command != null && command.getId() != null && command.getVersion() != null
                && command.getState() != null && !command.getState().terminal()) {
            if (!repository.markConflict(command.getId(), version(command), error(detail))) {
                QuotaSettlementCommand current = command.getFreezeId() == null
                        ? requireByRequest(command.getUserId(), command.getBillingRequestId())
                        : requireByFreezeId(command.getFreezeId());
                if (!current.getState().terminal()) {
                    throw new QuotaSettlementPersistenceException(
                            "failed to persist quota terminal conflict, commandId=" + command.getId(), cause);
                }
            }
        }
        QuotaSettlementConflictException conflict = new QuotaSettlementConflictException(detail);
        if (cause != null) {
            conflict.addSuppressed(cause);
        }
        return conflict;
    }

    private QuotaSettlementCommand requireByRequest(Long userId, String billingRequestId) {
        try {
            QuotaSettlementCommand command = repository.findByUserAndBillingRequestId(userId, billingRequestId);
            if (command == null) {
                throw new QuotaSettlementPersistenceException(
                        "durable quota reserve command is missing, billingRequestId=" + billingRequestId);
            }
            return command;
        } catch (QuotaSettlementPersistenceException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new QuotaSettlementPersistenceException("failed to load durable quota reserve command", failure);
        }
    }

    private QuotaSettlementCommand requireByFreezeId(String freezeId) {
        try {
            QuotaSettlementCommand command = repository.findByFreezeId(freezeId);
            if (command == null) {
                throw new QuotaSettlementPersistenceException(
                        "durable quota settlement command is missing, freezeId=" + freezeId);
            }
            return command;
        } catch (QuotaSettlementPersistenceException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new QuotaSettlementPersistenceException("failed to load durable quota settlement command", failure);
        }
    }

    private Reservation toReservation(QuotaSettlementCommand command) {
        if (StringUtils.isBlank(command.getFreezeId()) || value(command.getReservedMicrocredits()) <= 0L) {
            throw new QuotaSettlementPersistenceException(
                    "durable reservation is incomplete, commandId=" + command.getId());
        }
        return new Reservation(command.getFreezeId(), value(command.getReservedMicrocredits()));
    }

    private ReservationStatus toReservationStatus(QuotaSettlementCommand command) {
        return new ReservationStatus(
                command.getFreezeId(), command.getUserId(), value(command.getReservedMicrocredits()),
                value(command.getSettledMicrocredits()), toReservationState(command.getState()),
                command.getBillingRequestId());
    }

    private SettlementResult toSettlementResult(QuotaSettlementCommand command) {
        return new SettlementResult(
                command.getFreezeId(), toReservationState(command.getState()),
                value(command.getSettledMicrocredits()));
    }

    private ReservationState toReservationState(QuotaSettlementState state) {
        if (state == null) {
            return ReservationState.UNKNOWN;
        }
        return switch (state) {
            case RESERVE_PENDING, RESERVED, PROVIDER_STARTED, APPLY_PENDING -> ReservationState.PENDING;
            case CONFIRMED -> ReservationState.CONFIRMED;
            case RELEASED -> ReservationState.RELEASED;
            case RESERVE_FAILED, MANUAL_REVIEW, CONFLICT -> ReservationState.UNKNOWN;
        };
    }

    private void validateReserve(Long userId,
                                 long requestedMicrocredits,
                                 long minimumMicrocredits,
                                 String abilityCode) {
        if (userId == null || userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (requestedMicrocredits <= 0L || minimumMicrocredits <= 0L
                || minimumMicrocredits > requestedMicrocredits) {
            throw new IllegalArgumentException("invalid quota reserve amounts");
        }
        if (StringUtils.isBlank(abilityCode)) {
            throw new IllegalArgumentException("abilityCode must not be blank");
        }
    }

    private void validateApply(QuotaSettlementCommand command,
                               QuotaSettlementIntent intent,
                               long actualMicrocredits,
                               UsageMetadata usage) {
        if (intent == QuotaSettlementIntent.CONFIRM
                && (actualMicrocredits < 0L || actualMicrocredits > value(command.getReservedMicrocredits()))) {
            throw new IllegalArgumentException("actual quota exceeds durable reservation");
        }
        long charged = usage == null ? actualMicrocredits : usage.chargedMicrocredits();
        if ((intent == QuotaSettlementIntent.CONFIRM && charged != actualMicrocredits)
                || (intent == QuotaSettlementIntent.RELEASE && charged != 0L)) {
            throw new IllegalArgumentException("usage charge does not match terminal quota intent");
        }
    }

    private long retryDelaySeconds(QuotaSettlementCommand command) {
        return Math.min(300L, 1L << Math.min(8, Math.max(0, retryCount(command))));
    }

    private int retryCount(QuotaSettlementCommand command) {
        return command == null || command.getRetryCount() == null ? 0 : command.getRetryCount();
    }

    private int version(QuotaSettlementCommand command) {
        return command == null || command.getVersion() == null ? 0 : command.getVersion();
    }

    private long value(Long amount) {
        return amount == null ? 0L : amount;
    }

    private String normalizeOwner(String owner) {
        return owner == null ? null : owner.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTraceId(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return null;
        }
        String normalized = traceId.trim();
        if (normalized.length() > 64 || !normalized.matches("[A-Za-z0-9:_-]+")) {
            throw new IllegalArgumentException("traceId must be a compact correlation identifier");
        }
        return normalized;
    }

    private String error(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return error(message == null ? (failure == null ? null : failure.getClass().getSimpleName()) : message);
    }

    private String error(String message) {
        if (message == null || message.length() <= ERROR_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MAX_LENGTH);
    }
}
