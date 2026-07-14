package com.aigroup.member.benchmark;

import com.aigroup.member.MemberApplication;
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
    private static final int INITIAL_QUOTA = 1_000;
    private static final int CONCURRENT_UNIQUE_REQUESTS = 100;
    private static final int CONCURRENT_DUPLICATE_REQUESTS = 100;
    private static final int ABANDONED_FREEZES = 50;

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
                    period_quota_balance INT NOT NULL DEFAULT 0,
                    topup_quota_balance INT NOT NULL DEFAULT 0,
                    frozen_balance INT NOT NULL DEFAULT 0,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_user_id (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS quota_freeze (
                    freeze_id VARCHAR(64) NOT NULL,
                    user_id BIGINT NOT NULL,
                    amount INT NOT NULL,
                    ability_code VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    request_id VARCHAR(64) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (freeze_id),
                    UNIQUE KEY uk_user_request (user_id, request_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS quota_ledger (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    amount INT NOT NULL,
                    freeze_id VARCHAR(64) DEFAULT NULL,
                    ability_code VARCHAR(64) DEFAULT NULL,
                    remark VARCHAR(255) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_user_id (user_id),
                    KEY idx_freeze_id (freeze_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.update("DELETE FROM quota_ledger WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_freeze WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_account WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("""
                INSERT INTO quota_account
                    (user_id, period_quota_balance, topup_quota_balance, frozen_balance)
                VALUES (?, ?, 0, 0)
                """, USER_ID, INITIAL_QUOTA);
    }

    @AfterEach
    void cleanUpRows() {
        jdbcTemplate.update("DELETE FROM quota_ledger WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_freeze WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM quota_account WHERE user_id = ?", USER_ID);
    }

    @Test
    void shouldMeasureConcurrencyIdempotencyAndExpiredFreezeRecovery() throws Exception {
        List<TimedResult<String>> uniqueFreezes = runConcurrent(
                CONCURRENT_UNIQUE_REQUESTS,
                index -> () -> memberService.freeze(
                        USER_ID, "react", 1, "bench-unique-" + index).get("freezeId")
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

        String duplicateRequestId = "bench-duplicate-request";
        List<TimedResult<String>> duplicateFreezes = runConcurrent(
                CONCURRENT_DUPLICATE_REQUESTS,
                ignored -> () -> memberService.freeze(
                        USER_ID, "react", 1, duplicateRequestId).get("freezeId")
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
        int duplicateDeductions = Math.max(0, INITIAL_QUOTA - periodBalance() - 1);
        assertEquals(0, duplicateDeductions);

        for (int index = 0; index < ABANDONED_FREEZES; index++) {
            memberService.freeze(USER_ID, "react", 1, "bench-abandoned-" + index);
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

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("benchmarkType", "local-mysql-integration");
        report.put("environment", environment());
        report.put("dataset", Map.of(
                "concurrentUniqueFreezeRequests", CONCURRENT_UNIQUE_REQUESTS,
                "concurrentDuplicateFreezeRequests", CONCURRENT_DUPLICATE_REQUESTS,
                "concurrentDuplicateConfirmRequests", CONCURRENT_DUPLICATE_REQUESTS,
                "abandonedFreezeFaults", ABANDONED_FREEZES
        ));
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("uniqueFreezeSuccessRatePct", successRate(uniqueFreezes));
        results.put("uniqueFreezeLatencyP99Ms", percentile(uniqueFreezes, 99));
        results.put("duplicateFreezeSuccessRatePct", successRate(duplicateFreezes));
        results.put("duplicateFreezeLatencyP99Ms", percentile(duplicateFreezes, 99));
        results.put("duplicateConfirmSuccessRatePct", successRate(duplicateConfirms));
        results.put("duplicateConfirmLatencyP99Ms", percentile(duplicateConfirms, 99));
        results.put("distinctFreezeIdsForDuplicateRequest", duplicateFreezeIds.size());
        results.put("confirmLedgerRows", confirmLedgerRows);
        results.put("duplicateDeductions", duplicateDeductions);
        results.put("expiredFreezeReleaseSuccessRatePct",
                round1(100d * releasedFreezes / ABANDONED_FREEZES));
        results.put("expiredFreezeRecoveryDurationMs", recoveryDurationMs);
        results.put("finalFrozenBalance", frozenBalance());
        report.put("results", results);
        report.put("methodology", "Calls the transactional MemberService proxy against an isolated MySQL schema. Concurrent phases share one account to exercise row locks and the (user_id, request_id) unique guard. The production ExpiredFreezeReleaseJob is invoked after aging abandoned rows.");
        writeReport(report);
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

    private int frozenBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT frozen_balance FROM quota_account WHERE user_id = ?", Integer.class, USER_ID);
    }

    private int periodBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT period_quota_balance FROM quota_account WHERE user_id = ?", Integer.class, USER_ID);
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
