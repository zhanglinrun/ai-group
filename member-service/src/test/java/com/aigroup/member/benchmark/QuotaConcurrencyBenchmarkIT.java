package com.aigroup.member.benchmark;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.member.MemberApplication;
import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.job.ExpiredFreezeReleaseJob;
import com.aigroup.member.service.MemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in MySQL benchmark. It is intentionally named *IT so the normal unit
 * suite stays infrastructure-free. Use docs/evals/run-quota-benchmark.ps1,
 * which provisions a dedicated schema before invoking this test.
 */
@SpringBootTest(
        classes = MemberApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.task.scheduling.enabled=false"
        }
)
class QuotaConcurrencyBenchmarkIT {

    private static final long USER_ID = 9_100_000_001L;
    private static final long INITIAL_QUOTA = 3_000_000_000L;
    private static final int CONCURRENT_UNIQUE_REQUESTS = 100;
    private static final int CONCURRENT_DUPLICATE_REQUESTS = 100;
    private static final int CONCURRENT_TERMINAL_RACE_REQUESTS = 100;
    private static final int ABANDONED_FREEZES = 50;
    private static final int MANAGED_ABANDONED_FREEZES = 5;
    private static final int CONCURRENT_BENEFIT_EVENT_RACES = 100;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS quota_account (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    free_quota_balance BIGINT NOT NULL DEFAULT 0,
                    paid_quota_balance BIGINT NOT NULL DEFAULT 0,
                    frozen_balance BIGINT NOT NULL DEFAULT 0,
                    last_free_grant_month VARCHAR(7) DEFAULT NULL,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_user_id (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS quota_freeze (
                    freeze_id VARCHAR(64) NOT NULL,
                    user_id BIGINT NOT NULL,
                    amount BIGINT NOT NULL,
                    free_amount BIGINT NOT NULL,
                    paid_amount BIGINT NOT NULL,
                    settled_amount BIGINT NOT NULL DEFAULT 0,
                    requested_amount BIGINT DEFAULT NULL,
                    min_amount BIGINT DEFAULT NULL,
                    ability_code VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    request_id VARCHAR(64) DEFAULT NULL,
                    request_fingerprint VARCHAR(64) DEFAULT NULL,
                    owner_service VARCHAR(64) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (freeze_id),
                    UNIQUE KEY uk_user_request (user_id, request_id),
                    KEY idx_managed_expiry (owner_service, status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS quota_ledger (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    amount BIGINT NOT NULL,
                    freeze_id VARCHAR(64) DEFAULT NULL,
                    ability_code VARCHAR(64) DEFAULT NULL,
                    remark VARCHAR(255) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_user_id (user_id),
                    KEY idx_freeze_id (freeze_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS benefit_grant_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    idempotency_key VARCHAR(128) NOT NULL,
                    user_id BIGINT NOT NULL,
                    order_id VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    product_code VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    granted_quota BIGINT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_idempotency (idempotency_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.update("DELETE FROM benefit_grant_event WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_ledger WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_freeze WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_account WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("""
                INSERT INTO quota_account
                    (user_id, free_quota_balance, paid_quota_balance, frozen_balance)
                VALUES (?, ?, 0, 0)
                """, USER_ID, INITIAL_QUOTA);
    }

    @AfterEach
    void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM benefit_grant_event WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_ledger WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_freeze WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_account WHERE user_id = ?", USER_ID);
    }

    @Test
    void shouldMeasureConcurrencyIdempotencyAndExpiredFreezeRecovery() throws Exception {
        List<TimedResult<String>> uniqueFreezes = runConcurrent(
                CONCURRENT_UNIQUE_REQUESTS,
                index -> () -> memberService.freeze(
                        USER_ID, 1L, 1L, "react", "bench-unique-" + index).get("freezeId").toString()
        );
        assertAllSuccessful(uniqueFreezes);
        Set<String> uniqueFreezeIds = uniqueFreezes.stream()
                .map(TimedResult::value)
                .collect(Collectors.toSet());
        assertEquals(CONCURRENT_UNIQUE_REQUESTS, uniqueFreezeIds.size());
        assertEquals(CONCURRENT_UNIQUE_REQUESTS, frozenBalance());

        List<TimedResult<Void>> uniqueReleases = runConcurrent(
                uniqueFreezeIds.size(),
                index -> () -> {
                    memberService.release(uniqueFreezeIds.stream().sorted().toList().get(index));
                    return null;
                }
        );
        assertAllSuccessful(uniqueReleases);
        assertEquals(0, frozenBalance());

        List<TimedResult<Map<String, Object>>> shortenedReservations = runConcurrent(
                2,
                index -> () -> memberService.freeze(
                        USER_ID, 2_000_000_000L, 1L, "llm", "bench-shorten-" + index)
        );
        assertAllSuccessful(shortenedReservations);
        long shortenedTotal = shortenedReservations.stream()
                .map(TimedResult::value)
                .mapToLong(value -> ((Number) value.get("amount")).longValue())
                .sum();
        assertEquals(INITIAL_QUOTA, shortenedTotal,
                "concurrent reserve-up-to must never overbook the account");
        assertEquals(INITIAL_QUOTA, frozenBalance());
        for (TimedResult<Map<String, Object>> reservation : shortenedReservations) {
            memberService.release(reservation.value().get("freezeId").toString());
        }
        assertEquals(0L, frozenBalance());

        String duplicateRequestId = "bench-duplicate-request";
        List<TimedResult<String>> duplicateFreezes = runConcurrent(
                CONCURRENT_DUPLICATE_REQUESTS,
                ignored -> () -> memberService.freeze(
                        USER_ID, 1L, 1L, "react", duplicateRequestId).get("freezeId").toString()
        );
        assertAllSuccessful(duplicateFreezes);
        Set<String> duplicateFreezeIds = duplicateFreezes.stream()
                .map(TimedResult::value)
                .collect(Collectors.toSet());
        assertEquals(1, duplicateFreezeIds.size());
        String duplicateFreezeId = duplicateFreezeIds.iterator().next();
        assertEquals(1, count("SELECT COUNT(*) FROM quota_freeze WHERE user_id = ? AND request_id = ?",
                USER_ID, duplicateRequestId));
        assertEquals(1, frozenBalance());

        List<TimedResult<Void>> duplicateConfirms = runConcurrent(
                CONCURRENT_DUPLICATE_REQUESTS,
                ignored -> () -> {
                    memberService.confirm(duplicateFreezeId);
                    return null;
                }
        );
        assertAllSuccessful(duplicateConfirms);
        assertEquals(0, frozenBalance());
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM quota_freeze WHERE freeze_id = ?", String.class, duplicateFreezeId));
        int confirmLedgerRows = count(
                "SELECT COUNT(*) FROM quota_ledger WHERE user_id = ? AND freeze_id = ? AND type = 'CONFIRM'",
                USER_ID, duplicateFreezeId);
        assertEquals(1, confirmLedgerRows);
        long duplicateDeductions = Math.max(0L, INITIAL_QUOTA - freeBalance() - 1L);
        assertEquals(0L, duplicateDeductions);

        long balanceBeforeTerminalRace = freeBalance();
        String terminalRaceFreezeId = memberService.freeze(
                USER_ID, 10L, 10L, "llm", "bench-confirm-release-race")
                .get("freezeId").toString();
        List<TimedResult<Void>> terminalRace = runConcurrent(
                CONCURRENT_TERMINAL_RACE_REQUESTS,
                index -> () -> {
                    if (index % 2 == 0) {
                        memberService.confirm(terminalRaceFreezeId, 7L);
                    } else {
                        memberService.release(terminalRaceFreezeId);
                    }
                    return null;
                }
        );
        assertAllSuccessful(terminalRace);
        String terminalRaceStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM quota_freeze WHERE freeze_id = ?",
                String.class, terminalRaceFreezeId);
        int terminalRaceConfirmRows = count(
                "SELECT COUNT(*) FROM quota_ledger WHERE freeze_id = ? AND type = 'CONFIRM'",
                terminalRaceFreezeId);
        int terminalRaceReleaseRows = count(
                "SELECT COUNT(*) FROM quota_ledger WHERE freeze_id = ? AND type = 'RELEASE'",
                terminalRaceFreezeId);
        assertEquals(1, terminalRaceConfirmRows + terminalRaceReleaseRows,
                "confirm/release race must produce exactly one terminal ledger row");
        long expectedRaceDeduction = "CONFIRMED".equals(terminalRaceStatus) ? 7L : 0L;
        assertEquals(expectedRaceDeduction, balanceBeforeTerminalRace - freeBalance(),
                "confirm/release race must mutate the balance exactly once");
        assertEquals(0L, frozenBalance());

        for (int index = 0; index < ABANDONED_FREEZES; index++) {
            memberService.freeze(USER_ID, 1L, 1L, "react", "bench-abandoned-" + index);
        }
        assertEquals(ABANDONED_FREEZES, frozenBalance());
        jdbcTemplate.update("""
                UPDATE quota_freeze
                SET created_at = DATE_SUB(NOW(), INTERVAL 2 MINUTE)
                WHERE user_id = ? AND request_id LIKE 'bench-abandoned-%'
                """, USER_ID);

        long recoveryStarted = System.nanoTime();
        new ExpiredFreezeReleaseJob(memberService, 1, 100).releaseExpiredFreezes();
        long recoveryDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStarted);
        int releasedFreezes = count("""
                SELECT COUNT(*) FROM quota_freeze
                WHERE user_id = ? AND request_id LIKE 'bench-abandoned-%' AND status = 'RELEASED'
                """, USER_ID);
        assertEquals(ABANDONED_FREEZES, releasedFreezes);
        assertEquals(0, frozenBalance());

        List<String> managedFreezeIds = new ArrayList<>();
        for (int index = 0; index < MANAGED_ABANDONED_FREEZES; index++) {
            managedFreezeIds.add(memberService.freeze(
                    USER_ID, 1L, 1L, "llm", "bench-managed-" + index, "ai-agent")
                    .get("freezeId").toString());
        }
        jdbcTemplate.update("""
                UPDATE quota_freeze
                SET created_at = DATE_SUB(NOW(), INTERVAL 2 MINUTE)
                WHERE user_id = ? AND request_id LIKE 'bench-managed-%'
                """, USER_ID);
        new ExpiredFreezeReleaseJob(memberService, 1, 100).releaseExpiredFreezes();
        int pendingManagedFreezes = count("""
                SELECT COUNT(*) FROM quota_freeze
                WHERE user_id = ? AND request_id LIKE 'bench-managed-%' AND status = 'PENDING'
                """, USER_ID);
        assertEquals(MANAGED_ABANDONED_FREEZES, pendingManagedFreezes,
                "ai-agent managed reservations must be reconciled by its durable settlement owner");
        assertEquals(MANAGED_ABANDONED_FREEZES, frozenBalance());
        for (String managedFreezeId : managedFreezeIds) {
            memberService.release(managedFreezeId);
        }
        assertEquals(0, frozenBalance());

        List<TimedResult<Void>> benefitEventRaces = runConcurrent(
                CONCURRENT_BENEFIT_EVENT_RACES * 2,
                index -> () -> {
                    int orderIndex = index / 2;
                    String eventType = index % 2 == 0
                            ? CommonConstant.EVENT_GROUP_BUY_COMPLETED
                            : CommonConstant.EVENT_GROUP_BUY_REVOKED;
                    memberService.handleBenefitEvent(benefitEvent(
                            "bench-benefit-race-" + orderIndex, eventType));
                    return null;
                }
        );
        assertAllSuccessful(benefitEventRaces);
        int contradictoryBenefitOutcomes = count("""
                SELECT COUNT(*)
                FROM benefit_grant_event completed
                JOIN benefit_grant_event revoked ON revoked.order_id = completed.order_id
                WHERE completed.user_id = ?
                  AND completed.event_type = 'GROUP_BUY_COMPLETED'
                  AND completed.status = 'GRANTED'
                  AND revoked.event_type = 'GROUP_BUY_REVOKED'
                  AND revoked.status IN ('REVOKED', 'SKIPPED_REVOKED')
                """, USER_ID);
        assertEquals(0, contradictoryBenefitOutcomes,
                "one order must not commit both a quota grant and a revoke tombstone");
        int grantedBenefitOrders = count("""
                SELECT COUNT(*) FROM benefit_grant_event
                WHERE user_id = ? AND event_type = 'GROUP_BUY_COMPLETED' AND status = 'GRANTED'
                """, USER_ID);
        assertEquals(grantedBenefitOrders * 1_000_000L, paidBalance(),
                "paid quota must equal exactly one grant for each completed-first race winner");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("benchmarkType", "local-mysql-integration");
        report.put("environment", environment());
        report.put("dataset", Map.of(
                "concurrentUniqueFreezeRequests", CONCURRENT_UNIQUE_REQUESTS,
                "concurrentDuplicateFreezeRequests", CONCURRENT_DUPLICATE_REQUESTS,
                "concurrentDuplicateConfirmRequests", CONCURRENT_DUPLICATE_REQUESTS,
                "concurrentConfirmReleaseRaceRequests", CONCURRENT_TERMINAL_RACE_REQUESTS,
                "abandonedFreezeFaults", ABANDONED_FREEZES,
                "managedAbandonedFreezeFaults", MANAGED_ABANDONED_FREEZES,
                "concurrentBenefitEventRaceOrders", CONCURRENT_BENEFIT_EVENT_RACES
        ));
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("uniqueFreezeSuccessRatePct", successRate(uniqueFreezes));
        results.put("uniqueFreezeLatencyP99Ms", percentile(uniqueFreezes, 99));
        results.put("duplicateFreezeSuccessRatePct", successRate(duplicateFreezes));
        results.put("duplicateFreezeLatencyP99Ms", percentile(duplicateFreezes, 99));
        results.put("duplicateConfirmSuccessRatePct", successRate(duplicateConfirms));
        results.put("duplicateConfirmLatencyP99Ms", percentile(duplicateConfirms, 99));
        results.put("distinctFreezeIdsForDuplicateRequest", duplicateFreezeIds.size());
        results.put("concurrentShortenedReservationTotal", shortenedTotal);
        results.put("confirmLedgerRows", confirmLedgerRows);
        results.put("duplicateDeductions", duplicateDeductions);
        results.put("terminalRaceStatus", terminalRaceStatus);
        results.put("terminalRaceLedgerRows", terminalRaceConfirmRows + terminalRaceReleaseRows);
        results.put("terminalRaceBalanceDeduction", balanceBeforeTerminalRace - freeBalance());
        results.put("expiredFreezeReleaseSuccessRatePct",
                round1(100d * releasedFreezes / ABANDONED_FREEZES));
        results.put("expiredFreezeRecoveryDurationMs", recoveryDurationMs);
        results.put("managedExpiredFreezesPreserved", pendingManagedFreezes);
        results.put("contradictoryBenefitOutcomes", contradictoryBenefitOutcomes);
        results.put("benefitRaceGrantedOrders", grantedBenefitOrders);
        results.put("finalFrozenBalance", frozenBalance());
        report.put("results", results);
        report.put("methodology", "Calls the transactional MemberService proxy against an isolated MySQL schema. Concurrent phases share one account to exercise row locks and the (user_id, request_id) unique guard. COMPLETED/REVOKED pairs start concurrently and must serialize on quota_account before reading opposite event state. The production ExpiredFreezeReleaseJob is invoked after aging both legacy and ai-agent-managed rows; only legacy rows may be released automatically.");
        writeReport(report);
    }

    private TradeCompletedEvent benefitEvent(String orderId, String eventType) {
        TradeCompletedEvent event = new TradeCompletedEvent();
        event.setEventType(eventType);
        event.setUserId(USER_ID);
        event.setOrderId(orderId);
        event.setProductCode("QUOTA_BENCH");
        event.setBaseQuota(1L);
        event.setBonusQuota(0L);
        return event;
    }

    private <T> List<TimedResult<T>> runConcurrent(int count,
                                                    IntFunction<Callable<T>> operationFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TimedResult<T>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                Callable<T> operation = operationFactory.apply(index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    long started = System.nanoTime();
                    try {
                        return new TimedResult<>(
                                operation.call(),
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                                null
                        );
                    } catch (Throwable throwable) {
                        return new TimedResult<>(
                                null,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                                throwable
                        );
                    }
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not become ready");
            start.countDown();
            List<TimedResult<T>> results = new ArrayList<>();
            for (Future<TimedResult<T>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void assertAllSuccessful(List<? extends TimedResult<?>> results) {
        List<Throwable> failures = results.stream()
                .map(TimedResult::failure)
                .filter(failure -> failure != null)
                .toList();
        assertTrue(failures.isEmpty(), () -> "concurrent failures: " + failures);
    }

    private long frozenBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT frozen_balance FROM quota_account WHERE user_id = ?", Long.class, USER_ID);
    }

    private long freeBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT free_quota_balance FROM quota_account WHERE user_id = ?", Long.class, USER_ID);
    }

    private long paidBalance() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT paid_quota_balance FROM quota_account WHERE user_id = ?",
                Long.class,
                USER_ID);
        return value == null ? 0L : value;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private double successRate(List<? extends TimedResult<?>> results) {
        long successes = results.stream().filter(result -> result.failure() == null).count();
        return round1(100d * successes / Math.max(1, results.size()));
    }

    private long percentile(List<? extends TimedResult<?>> results, int percentile) {
        List<Long> sorted = results.stream().map(TimedResult::latencyMs).sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile / 100d * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private Map<String, Object> environment() {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("java", System.getProperty("java.version"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("database", "MySQL " + jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
        return environment;
    }

    private void writeReport(Map<String, Object> report) throws Exception {
        Path reportPath = Path.of(System.getProperty(
                "resume.eval.report",
                "target/resume-evals/quota-benchmark.json"
        )).toAbsolutePath().normalize();
        Files.createDirectories(reportPath.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(reportPath.toFile(), report);
        System.out.println("RESUME_QUOTA_REPORT=" + reportPath);
    }

    private record TimedResult<T>(T value, long latencyMs, Throwable failure) {
    }
}
