package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.quota.DurableQuotaBillingCoordinator;
import com.linrun.agent.domain.agent.quota.IQuotaSettlementCommandRepository;
import com.linrun.agent.domain.agent.quota.QuotaBillingRequestId;
import com.linrun.agent.domain.agent.quota.QuotaProviderAlreadyStartedException;
import com.linrun.agent.domain.agent.quota.QuotaSettlementCommand;
import com.linrun.agent.domain.agent.quota.QuotaSettlementConflictException;
import com.linrun.agent.domain.agent.quota.QuotaSettlementIntent;
import com.linrun.agent.domain.agent.quota.QuotaSettlementPersistenceException;
import com.linrun.agent.domain.agent.quota.QuotaSettlementRecoveryService;
import com.linrun.agent.domain.agent.quota.QuotaSettlementRemotePort;
import com.linrun.agent.domain.agent.quota.QuotaSettlementState;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class DurableQuotaBillingCoordinatorTest {

    @Test
    public void shouldFailClosedBeforeRemoteFreezeWhenIntentInsertFails() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.failInsert = true;
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);

        try {
            coordinator.reserve(1001L, 100_000L, 10_000L, "llm_call", "billing-insert-failed");
            Assert.fail("reserve persistence failure must prevent the remote freeze");
        } catch (QuotaSettlementPersistenceException expected) {
            Assert.assertTrue(expected.getMessage().contains("persist quota reserve intent"));
        }

        Assert.assertEquals(0, remote.reserveCalls);
    }

    @Test
    public void shouldPersistReserveIntentBeforeRemoteFreezeAndNormalizeAbility() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);

        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 100_000L, 10_000L, "  IMAGE_GENERATION  ", "billing-1");

        Assert.assertTrue(remote.reserveObservedDurableIntent.get());
        Assert.assertEquals("image_generation", remote.lastAbility);
        Assert.assertEquals("freeze-billing-1", reservation.freezeId());
        Assert.assertEquals(QuotaSettlementState.RESERVED,
                repository.byRequest(1001L, "billing-1").getState());
    }

    @Test
    public void shouldRecoverLostFreezeResponseByStableBillingRequestId() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        remote.throwAfterFreeze = true;
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);

        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-lost");

        Assert.assertEquals("freeze-billing-lost", reservation.freezeId());
        Assert.assertEquals(1, remote.findByRequestCalls);
        Assert.assertEquals(QuotaSettlementState.RESERVED,
                repository.byRequest(1001L, "billing-lost").getState());
    }

    @Test
    public void shouldRejectConfirmWhenProviderStartedWasNotPersisted() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-no-start");

        try {
            coordinator.settle(reservation.freezeId(), 5_000L);
            Assert.fail("confirm without PROVIDER_STARTED must fail closed");
        } catch (QuotaSettlementConflictException expected) {
            Assert.assertTrue(expected.getMessage().contains("conflicts"));
        }

        Assert.assertEquals(0, remote.confirmCalls);
        Assert.assertEquals(0, remote.releaseCalls);
    }

    @Test(expected = QuotaProviderAlreadyStartedException.class)
    public void shouldConsumeProviderAdmissionOnlyOnce() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-double-start");

        coordinator.markProviderStarted(reservation.freezeId());
        coordinator.markProviderStarted(reservation.freezeId());
    }

    @Test
    public void shouldDurablyAcceptTransientConfirmFailureWithoutOppositeRelease() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-pending");
        coordinator.markProviderStarted(reservation.freezeId());
        remote.confirmFailure = new IllegalStateException("network reset");
        remote.findByFreezeFailure = new IllegalStateException("query timeout");

        QuotaBillingPort.SettlementResult result = coordinator.settleWithUsage(
                reservation.freezeId(), 5_000L,
                new QuotaBillingPort.UsageMetadata(
                        99L, 5L, 30L, 700, 50, "ESTIMATED", 5_000L));

        Assert.assertEquals(QuotaBillingPort.ReservationState.PENDING, result.state());
        QuotaSettlementCommand command = repository.byFreeze(reservation.freezeId());
        Assert.assertEquals(QuotaSettlementState.APPLY_PENDING, command.getState());
        Assert.assertEquals(Long.valueOf(99L), command.getLlmInvocationId());
        Assert.assertEquals(Long.valueOf(5_000L), command.getChargedMicrocredits());
        Assert.assertEquals(0, remote.releaseCalls);
    }

    @Test
    public void shouldConvergeWhenConfirmSucceededButItsResponseWasLost() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-confirm-lost");
        coordinator.markProviderStarted(reservation.freezeId());
        remote.throwAfterConfirm = true;

        QuotaBillingPort.SettlementResult result = coordinator.settleWithUsage(
                reservation.freezeId(), 5_000L,
                new QuotaBillingPort.UsageMetadata(
                        100L, 5L, 30L, 700, 50, "PROVIDER", 5_000L));

        Assert.assertEquals(QuotaBillingPort.ReservationState.CONFIRMED, result.state());
        Assert.assertEquals(5_000L, result.settledMicrocredits());
        Assert.assertEquals(QuotaSettlementState.CONFIRMED,
                repository.byFreeze(reservation.freezeId()).getState());
        Assert.assertEquals(1, remote.confirmCalls);
        Assert.assertEquals(0, remote.releaseCalls);
    }

    @Test
    public void shouldPersistConflictWhenMemberAlreadyReachedTheOppositeTerminalState() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-opposite-terminal");
        coordinator.markProviderStarted(reservation.freezeId());
        remote.forceState(reservation.freezeId(), QuotaBillingPort.ReservationState.RELEASED, 0L);

        try {
            coordinator.settle(reservation.freezeId(), 5_000L);
            Assert.fail("opposite member terminal state must be a durable conflict");
        } catch (QuotaSettlementConflictException expected) {
            Assert.assertTrue(expected.getMessage().contains("terminal state conflicts"));
        }

        Assert.assertEquals(QuotaSettlementState.CONFLICT,
                repository.byFreeze(reservation.freezeId()).getState());
        Assert.assertEquals(1, remote.confirmCalls);
        Assert.assertEquals(0, remote.releaseCalls);
    }

    @Test
    public void shouldReleaseExpiredReservedCommandBecauseProviderWasNeverAdmitted() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "deep_search", "billing-reserved-crash");
        QuotaSettlementRecoveryService recovery = new QuotaSettlementRecoveryService(repository, coordinator);

        int attempted = recovery.recoverDue(Duration.ofSeconds(30), 10);

        Assert.assertEquals(1, attempted);
        Assert.assertEquals(1, remote.releaseCalls);
        Assert.assertEquals(QuotaSettlementState.RELEASED,
                repository.byFreeze(reservation.freezeId()).getState());
        Assert.assertEquals("PROVIDER_NOT_STARTED",
                repository.byFreeze(reservation.freezeId()).getUsageSource());
    }

    @Test
    public void shouldNeverAutoSettleUnknownProviderOutcome() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "image_generation", "billing-worker-lost");
        coordinator.markProviderStarted(reservation.freezeId());
        QuotaSettlementRecoveryService recovery = new QuotaSettlementRecoveryService(repository, coordinator);

        recovery.recoverDue(Duration.ofSeconds(30), 10);

        Assert.assertEquals(QuotaSettlementState.MANUAL_REVIEW,
                repository.byFreeze(reservation.freezeId()).getState());
        Assert.assertEquals(0, remote.confirmCalls);
        Assert.assertEquals(0, remote.releaseCalls);
    }

    @Test
    public void shouldEscalatePendingTerminalApplyAfterMaximumRetries() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaBillingPort.Reservation reservation = coordinator.reserve(
                1001L, 50_000L, 10_000L, "llm_call", "billing-max-retry");
        coordinator.markProviderStarted(reservation.freezeId());
        remote.confirmFailure = new IllegalStateException("network reset");
        remote.findByFreezeFailure = new IllegalStateException("query timeout");
        coordinator.settle(reservation.freezeId(), 5_000L);
        QuotaSettlementCommand pending = repository.byFreeze(reservation.freezeId());
        pending.setRetryCount(10);
        remote.confirmFailure = null;
        remote.findByFreezeFailure = null;
        int confirmsBeforeRecovery = remote.confirmCalls;

        new QuotaSettlementRecoveryService(repository, coordinator)
                .recoverDue(Duration.ofSeconds(30), 10);

        Assert.assertEquals(QuotaSettlementState.MANUAL_REVIEW, pending.getState());
        Assert.assertEquals(confirmsBeforeRecovery, remote.confirmCalls);
        Assert.assertEquals(0, remote.releaseCalls);
    }

    @Test
    public void shouldResetRetryBudgetWhenSettlementMovesToANewPhase() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        QuotaSettlementCommand pending = command(
                1001L, "billing-phase-retry", "llm_call", 50_000L, 10_000L);
        pending.setRetryCount(9);
        repository.insertIfAbsent(pending);
        remote.notFoundRequests.add("billing-phase-retry");
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaSettlementRecoveryService recovery = new QuotaSettlementRecoveryService(repository, coordinator);

        recovery.recoverDue(Duration.ofSeconds(30), 10);
        QuotaSettlementCommand reserved = repository.byRequest(1001L, "billing-phase-retry");
        Assert.assertEquals(QuotaSettlementState.RESERVED, reserved.getState());
        Assert.assertEquals(Integer.valueOf(0), reserved.getRetryCount());

        coordinator.markProviderStarted(reserved.getFreezeId());
        remote.confirmFailure = new IllegalStateException("network reset");
        remote.findByFreezeFailure = new IllegalStateException("query timeout");
        coordinator.settle(reserved.getFreezeId(), 5_000L);

        QuotaSettlementCommand applyPending = repository.byFreeze(reserved.getFreezeId());
        Assert.assertEquals(QuotaSettlementState.APPLY_PENDING, applyPending.getState());
        Assert.assertEquals(Integer.valueOf(1), applyPending.getRetryCount());

        recovery.recoverDue(Duration.ofSeconds(30), 10);
        Assert.assertEquals(QuotaSettlementState.APPLY_PENDING, applyPending.getState());
        Assert.assertEquals(Integer.valueOf(2), applyPending.getRetryCount());
    }

    @Test
    public void shouldRecoverCommittedFreezeBeforeApplyingTheRetryLimit() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        QuotaSettlementCommand pending = command(
                1001L, "billing-final-reserve-attempt", "llm_call", 50_000L, 10_000L);
        pending.setRetryCount(9);
        repository.insertIfAbsent(pending);
        remote.throwAfterFreeze = true;
        remote.failLookupAfterLostFreezeResponse = true;
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaSettlementRecoveryService recovery = new QuotaSettlementRecoveryService(repository, coordinator);

        recovery.recoverDue(Duration.ofSeconds(30), 10);
        QuotaSettlementCommand ambiguous = repository.byRequest(1001L, "billing-final-reserve-attempt");
        Assert.assertEquals(QuotaSettlementState.RESERVE_PENDING, ambiguous.getState());
        Assert.assertEquals(Integer.valueOf(10), ambiguous.getRetryCount());
        Assert.assertEquals(1, remote.reserveCalls);

        remote.throwAfterFreeze = false;
        recovery.recoverDue(Duration.ofSeconds(30), 10);

        QuotaSettlementCommand recovered = repository.byRequest(1001L, "billing-final-reserve-attempt");
        Assert.assertEquals(QuotaSettlementState.RESERVED, recovered.getState());
        Assert.assertEquals("freeze-billing-final-reserve-attempt", recovered.getFreezeId());
        Assert.assertEquals(Integer.valueOf(0), recovered.getRetryCount());
        Assert.assertEquals(1, remote.reserveCalls);
    }

    @Test
    public void shouldReissueTheSameFreezeAfterCrashBeforeTheFirstMemberCall() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeRemote remote = new FakeRemote(repository);
        QuotaSettlementCommand pending = command(
                1001L, "billing-before-freeze", "llm_call", 50_000L, 10_000L);
        repository.insertIfAbsent(pending);
        remote.notFoundRequests.add("billing-before-freeze");
        DurableQuotaBillingCoordinator coordinator = new DurableQuotaBillingCoordinator(repository, remote);
        QuotaSettlementRecoveryService recovery = new QuotaSettlementRecoveryService(repository, coordinator);

        recovery.recoverDue(Duration.ofSeconds(30), 10);

        QuotaSettlementCommand current = repository.byRequest(1001L, "billing-before-freeze");
        Assert.assertEquals(QuotaSettlementState.RESERVED, current.getState());
        Assert.assertEquals(Integer.valueOf(0), current.getRetryCount());
        Assert.assertEquals(1, remote.findByRequestCalls);
        Assert.assertEquals(1, remote.reserveCalls);
    }

    private static QuotaSettlementCommand command(Long userId,
                                                  String requestId,
                                                  String ability,
                                                  long requested,
                                                  long minimum) {
        return QuotaSettlementCommand.builder()
                .userId(userId)
                .billingRequestId(requestId)
                .requestFingerprint(QuotaBillingRequestId.fingerprint(userId, ability, requested, minimum))
                .abilityCode(ability)
                .requestedMicrocredits(requested)
                .minimumMicrocredits(minimum)
                .reservedMicrocredits(0L)
                .intendedAction(QuotaSettlementIntent.NONE)
                .intendedMicrocredits(0L)
                .settledMicrocredits(0L)
                .chargedMicrocredits(0L)
                .state(QuotaSettlementState.RESERVE_PENDING)
                .retryCount(0)
                .version(0)
                .build();
    }

    private static final class FakeRemote implements QuotaSettlementRemotePort {

        private final InMemoryRepository repository;
        private final Map<String, RemoteReservationStatus> remoteByRequest = new LinkedHashMap<>();
        private final Map<String, RemoteReservationStatus> remoteByFreeze = new LinkedHashMap<>();
        private final List<String> notFoundRequests = new ArrayList<>();
        private final AtomicBoolean reserveObservedDurableIntent = new AtomicBoolean();
        private boolean throwAfterFreeze;
        private boolean failLookupAfterLostFreezeResponse;
        private boolean throwAfterConfirm;
        private RuntimeException confirmFailure;
        private RuntimeException findByFreezeFailure;
        private String lastAbility;
        private int findByRequestCalls;
        private int findByRequestFailuresRemaining;
        private int reserveCalls;
        private int confirmCalls;
        private int releaseCalls;

        private FakeRemote(InMemoryRepository repository) {
            this.repository = repository;
        }

        @Override
        public RemoteReservationStatus reserveRemote(Long userId,
                                                     long requestedMicrocredits,
                                                     long minimumMicrocredits,
                                                     String abilityCode,
                                                     String billingRequestId) {
            reserveCalls++;
            reserveObservedDurableIntent.set(repository.byRequest(userId, billingRequestId) != null);
            lastAbility = abilityCode;
            RemoteReservationStatus status = snapshot(
                    "freeze-" + billingRequestId,
                    userId,
                    requestedMicrocredits,
                    minimumMicrocredits,
                    requestedMicrocredits,
                    0L,
                    abilityCode,
                    QuotaBillingPort.ReservationState.PENDING,
                    billingRequestId);
            remoteByRequest.put(billingRequestId, status);
            remoteByFreeze.put(status.freezeId(), status);
            if (throwAfterFreeze) {
                if (failLookupAfterLostFreezeResponse) {
                    findByRequestFailuresRemaining++;
                }
                throw new IllegalStateException("freeze response lost");
            }
            return status;
        }

        @Override
        public RemoteReservationStatus confirmRemote(String freezeId, long actualMicrocredits) {
            confirmCalls++;
            if (confirmFailure != null) {
                throw confirmFailure;
            }
            RemoteReservationStatus pending = remoteByFreeze.get(freezeId);
            if (pending.state() != QuotaBillingPort.ReservationState.PENDING) {
                return pending;
            }
            RemoteReservationStatus confirmed = copyWithState(
                    pending, QuotaBillingPort.ReservationState.CONFIRMED, actualMicrocredits);
            remoteByFreeze.put(freezeId, confirmed);
            remoteByRequest.put(confirmed.billingRequestId(), confirmed);
            if (throwAfterConfirm) {
                throw new IllegalStateException("confirm response lost");
            }
            return confirmed;
        }

        @Override
        public RemoteReservationStatus releaseRemote(String freezeId) {
            releaseCalls++;
            RemoteReservationStatus pending = remoteByFreeze.get(freezeId);
            if (pending.state() != QuotaBillingPort.ReservationState.PENDING) {
                return pending;
            }
            RemoteReservationStatus released = copyWithState(
                    pending, QuotaBillingPort.ReservationState.RELEASED, 0L);
            remoteByFreeze.put(freezeId, released);
            remoteByRequest.put(released.billingRequestId(), released);
            return released;
        }

        private void forceState(String freezeId,
                                QuotaBillingPort.ReservationState state,
                                long settledMicrocredits) {
            RemoteReservationStatus current = remoteByFreeze.get(freezeId);
            RemoteReservationStatus terminal = copyWithState(current, state, settledMicrocredits);
            remoteByFreeze.put(freezeId, terminal);
            remoteByRequest.put(terminal.billingRequestId(), terminal);
        }

        @Override
        public RemoteReservationStatus findByRequestRemote(Long userId, String billingRequestId) {
            findByRequestCalls++;
            if (findByRequestFailuresRemaining > 0) {
                findByRequestFailuresRemaining--;
                throw new IllegalStateException("freeze lookup temporarily unavailable");
            }
            if (notFoundRequests.contains(billingRequestId)) {
                return notFound(null, userId, billingRequestId);
            }
            return remoteByRequest.getOrDefault(
                    billingRequestId, notFound(null, userId, billingRequestId));
        }

        @Override
        public RemoteReservationStatus findByFreezeIdRemote(String freezeId) {
            if (findByFreezeFailure != null) {
                throw findByFreezeFailure;
            }
            return remoteByFreeze.getOrDefault(freezeId, notFound(freezeId, null, null));
        }

        private RemoteReservationStatus snapshot(String freezeId,
                                                 Long userId,
                                                 long requested,
                                                 long minimum,
                                                 long reserved,
                                                 long settled,
                                                 String ability,
                                                 QuotaBillingPort.ReservationState state,
                                                 String requestId) {
            return new RemoteReservationStatus(
                    freezeId, userId, reserved, settled, requested, minimum, ability, state,
                    requestId,
                    QuotaBillingRequestId.fingerprint(userId, ability, requested, minimum),
                    "ai-agent");
        }

        private RemoteReservationStatus copyWithState(RemoteReservationStatus source,
                                                      QuotaBillingPort.ReservationState state,
                                                      long settled) {
            return new RemoteReservationStatus(
                    source.freezeId(), source.userId(), source.reservedMicrocredits(), settled,
                    source.requestedMicrocredits(), source.minimumMicrocredits(), source.abilityCode(),
                    state, source.billingRequestId(), source.requestFingerprint(), source.ownerService());
        }

        private RemoteReservationStatus notFound(String freezeId, Long userId, String requestId) {
            return new RemoteReservationStatus(
                    freezeId, userId, 0L, 0L, null, null, null,
                    QuotaBillingPort.ReservationState.NOT_FOUND, requestId, null, null);
        }
    }

    private static final class InMemoryRepository implements IQuotaSettlementCommandRepository {

        private final Map<Long, QuotaSettlementCommand> commands = new LinkedHashMap<>();
        private long sequence;
        private boolean failInsert;

        @Override
        public synchronized boolean insertIfAbsent(QuotaSettlementCommand command) {
            if (failInsert) {
                throw new IllegalStateException("insert unavailable");
            }
            if (byRequest(command.getUserId(), command.getBillingRequestId()) != null) {
                return false;
            }
            command.setId(++sequence);
            command.setVersion(0);
            commands.put(command.getId(), command);
            return true;
        }

        @Override
        public synchronized QuotaSettlementCommand findByUserAndBillingRequestId(Long userId, String billingRequestId) {
            return byRequest(userId, billingRequestId);
        }

        @Override
        public synchronized QuotaSettlementCommand findByFreezeId(String freezeId) {
            return byFreeze(freezeId);
        }

        @Override
        public synchronized boolean markReserved(Long id, int version, String freezeId, long reservedMicrocredits) {
            QuotaSettlementCommand command = cas(id, version, QuotaSettlementState.RESERVE_PENDING);
            if (command == null) return false;
            command.setFreezeId(freezeId);
            command.setReservedMicrocredits(reservedMicrocredits);
            command.setState(QuotaSettlementState.RESERVED);
            command.setRetryCount(0);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean markReserveFailed(Long id, int version, String lastError) {
            QuotaSettlementCommand command = cas(id, version, QuotaSettlementState.RESERVE_PENDING);
            if (command == null) return false;
            command.setState(QuotaSettlementState.RESERVE_FAILED);
            command.setLastError(lastError);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean markProviderStarted(Long id, int version, long manualReviewMinutes) {
            QuotaSettlementCommand command = cas(id, version, QuotaSettlementState.RESERVED);
            if (command == null) return false;
            command.setState(QuotaSettlementState.PROVIDER_STARTED);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean persistIntent(Long id,
                                                  int version,
                                                  QuotaSettlementIntent intent,
                                                  long intendedMicrocredits,
                                                  Long llmInvocationId,
                                                  Long inputRateSnapshot,
                                                  Long outputRateSnapshot,
                                                  Integer promptTokens,
                                                  Integer completionTokens,
                                                  String usageSource,
                                                  long chargedMicrocredits) {
            QuotaSettlementCommand command = commands.get(id);
            if (command == null || !Objects.equals(command.getVersion(), version)
                    || (command.getState() != QuotaSettlementState.PROVIDER_STARTED
                    && !(command.getState() == QuotaSettlementState.RESERVED
                    && intent == QuotaSettlementIntent.RELEASE))) {
                return false;
            }
            command.setIntendedAction(intent);
            command.setIntendedMicrocredits(intendedMicrocredits);
            command.setLlmInvocationId(llmInvocationId);
            command.setInputRateSnapshot(inputRateSnapshot);
            command.setOutputRateSnapshot(outputRateSnapshot);
            command.setPromptTokens(promptTokens);
            command.setCompletionTokens(completionTokens);
            command.setUsageSource(usageSource);
            command.setChargedMicrocredits(chargedMicrocredits);
            command.setState(QuotaSettlementState.APPLY_PENDING);
            command.setRetryCount(0);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean markTerminal(Long id,
                                                 int version,
                                                 QuotaSettlementState terminalState,
                                                 long settledMicrocredits) {
            QuotaSettlementCommand command = cas(id, version, QuotaSettlementState.APPLY_PENDING);
            if (command == null) return false;
            command.setState(terminalState);
            command.setSettledMicrocredits(settledMicrocredits);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean markConflict(Long id, int version, String lastError) {
            QuotaSettlementCommand command = commands.get(id);
            if (command == null || !Objects.equals(command.getVersion(), version) || command.getState().terminal()) {
                return false;
            }
            command.setState(QuotaSettlementState.CONFLICT);
            command.setLastError(lastError);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean markManualReview(Long id, int version, String lastError) {
            QuotaSettlementCommand command = commands.get(id);
            if (command == null || !Objects.equals(command.getVersion(), version)
                    || !(command.getState() == QuotaSettlementState.RESERVE_PENDING
                    || command.getState() == QuotaSettlementState.PROVIDER_STARTED
                    || command.getState() == QuotaSettlementState.APPLY_PENDING)) {
                return false;
            }
            command.setState(QuotaSettlementState.MANUAL_REVIEW);
            command.setLastError(lastError);
            bump(command);
            return true;
        }

        @Override
        public synchronized boolean scheduleRetry(Long id, int version, long retryDelaySeconds, String lastError) {
            QuotaSettlementCommand command = commands.get(id);
            if (command == null || !Objects.equals(command.getVersion(), version)
                    || !(command.getState() == QuotaSettlementState.RESERVE_PENDING
                    || command.getState() == QuotaSettlementState.APPLY_PENDING)) {
                return false;
            }
            command.setRetryCount((command.getRetryCount() == null ? 0 : command.getRetryCount()) + 1);
            command.setLastError(lastError);
            bump(command);
            return true;
        }

        @Override
        public synchronized List<QuotaSettlementCommand> claimDue(String leaseOwner,
                                                                  long leaseSeconds,
                                                                  int batchLimit) {
            List<QuotaSettlementCommand> claimed = new ArrayList<>();
            for (QuotaSettlementCommand command : commands.values()) {
                if (claimed.size() >= batchLimit) break;
                if (command.getState() == QuotaSettlementState.RESERVE_PENDING
                        || command.getState() == QuotaSettlementState.RESERVED
                        || command.getState() == QuotaSettlementState.PROVIDER_STARTED
                        || command.getState() == QuotaSettlementState.APPLY_PENDING) {
                    command.setLeaseOwner(leaseOwner);
                    bump(command);
                    claimed.add(command);
                }
            }
            return claimed;
        }

        private QuotaSettlementCommand byRequest(Long userId, String requestId) {
            return commands.values().stream()
                    .filter(command -> Objects.equals(userId, command.getUserId())
                            && Objects.equals(requestId, command.getBillingRequestId()))
                    .findFirst().orElse(null);
        }

        private QuotaSettlementCommand byFreeze(String freezeId) {
            return commands.values().stream()
                    .filter(command -> Objects.equals(freezeId, command.getFreezeId()))
                    .findFirst().orElse(null);
        }

        private QuotaSettlementCommand cas(Long id, int version, QuotaSettlementState state) {
            QuotaSettlementCommand command = commands.get(id);
            return command != null && Objects.equals(command.getVersion(), version)
                    && command.getState() == state ? command : null;
        }

        private void bump(QuotaSettlementCommand command) {
            command.setVersion((command.getVersion() == null ? 0 : command.getVersion()) + 1);
        }
    }
}
