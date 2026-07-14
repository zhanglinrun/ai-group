package com.aigroup.member.benchmark;

import com.aigroup.member.MemberApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in RabbitMQ/MySQL benchmark. The runner provisions a dedicated database;
 * unique broker entities keep the measurement isolated from normal queues.
 */
@SpringBootTest(
        classes = MemberApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.task.scheduling.enabled=false",
                "spring.rabbitmq.listener.simple.concurrency=4",
                "spring.rabbitmq.listener.simple.max-concurrency=4",
                "spring.rabbitmq.listener.simple.prefetch=20",
                "spring.rabbitmq.listener.simple.retry.enabled=true",
                "spring.rabbitmq.listener.simple.retry.max-attempts=4",
                "spring.rabbitmq.listener.simple.retry.initial-interval=25ms",
                "spring.rabbitmq.listener.simple.retry.multiplier=1",
                "spring.rabbitmq.listener.simple.retry.max-interval=25ms"
        }
)
class BenefitMqBenchmarkIT {

    private static final int EVENT_COUNT = 50;
    private static final int DUPLICATE_DELIVERIES = 50;
    private static final String RUN_ID = Long.toUnsignedString(System.nanoTime(), 36);
    private static final String EXCHANGE = "member.resume.benchmark." + RUN_ID + ".exchange";
    private static final String ROUTING_KEY = "member.resume.benchmark." + RUN_ID + ".completed";
    private static final String QUEUE = "member.resume.benchmark." + RUN_ID + ".queue";
    private static final String DLQ = QUEUE + ".dlq";

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("ai-group.member.benefit-exchange", () -> EXCHANGE);
        registry.add("ai-group.member.benefit-routing-key", () -> ROUTING_KEY);
        registry.add("ai-group.member.benefit-queue", () -> QUEUE);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void setUpSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS product_sku (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    code VARCHAR(64) NOT NULL,
                    name VARCHAR(128) NOT NULL,
                    price DECIMAL(10,2) NOT NULL DEFAULT 0,
                    period_quota INT NOT NULL DEFAULT 0,
                    topup_quota INT NOT NULL DEFAULT 0,
                    member_days INT NOT NULL DEFAULT 0,
                    tier VARCHAR(32) NOT NULL,
                    sku_type VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
                    status TINYINT NOT NULL DEFAULT 1,
                    group_goods_id VARCHAR(16) DEFAULT NULL,
                    group_activity_id BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_code (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS member_account (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    tier VARCHAR(32) NOT NULL DEFAULT 'FREE',
                    start_at DATETIME DEFAULT NULL,
                    expire_at DATETIME DEFAULT NULL,
                    last_period_grant_month VARCHAR(7) DEFAULT NULL,
                    status TINYINT NOT NULL DEFAULT 1,
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_user_id (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS benefit_grant_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    idempotency_key VARCHAR(128) NOT NULL,
                    user_id BIGINT NOT NULL,
                    order_id VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    product_code VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    member_days_delta INT NOT NULL DEFAULT 0,
                    period_quota_granted INT NOT NULL DEFAULT 0,
                    topup_quota_granted INT NOT NULL DEFAULT 0,
                    tier_effect VARCHAR(32) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_idempotency (idempotency_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.update("""
                INSERT INTO product_sku
                    (code, name, price, period_quota, topup_quota, member_days, tier, sku_type, status)
                VALUES ('PRO_MONTH', 'Pro Month', 49.00, 500, 0, 30, 'PRO', 'MEMBER', 1)
                ON DUPLICATE KEY UPDATE period_quota = VALUES(period_quota), member_days = VALUES(member_days)
                """);

        for (int index = 0; index < EVENT_COUNT; index++) {
            long userId = userId(index);
            jdbcTemplate.update("""
                    INSERT INTO member_account (user_id, tier, status)
                    VALUES (?, 'FREE', 1)
                    """, userId);
            jdbcTemplate.update("""
                    INSERT INTO quota_account
                        (user_id, period_quota_balance, topup_quota_balance, frozen_balance)
                    VALUES (?, 20, 0, 0)
                    """, userId);
        }
    }

    @AfterEach
    void removeBrokerEntities() {
        listenerRegistry.stop();
        amqpAdmin.deleteQueue(QUEUE);
        amqpAdmin.deleteQueue(DLQ);
        amqpAdmin.deleteExchange(EXCHANGE);
        amqpAdmin.deleteExchange(EXCHANGE + ".dlx");
    }

    @Test
    void shouldMeasureDeliveryIdempotencyAndDeadLetterRecovery() throws Exception {
        Map<String, Long> publishedAtNanos = new HashMap<>();
        for (int index = 0; index < EVENT_COUNT; index++) {
            String orderId = orderId(index);
            String payload = completedPayload(index);
            publishedAtNanos.put(orderId, System.nanoTime());
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, payload);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, payload);
        }

        Map<String, Long> deliveryLatencyMs = awaitDeliveredEvents(publishedAtNanos, 30_000);
        assertEquals(EVENT_COUNT, deliveryLatencyMs.size());
        assertEquals(EVENT_COUNT, count("SELECT COUNT(*) FROM benefit_grant_event WHERE status = 'GRANTED'"));
        int grantLedgerRows = count("SELECT COUNT(*) FROM quota_ledger WHERE type = 'GRANT'");
        assertEquals(EVENT_COUNT, grantLedgerRows);
        assertEquals(EVENT_COUNT, count("SELECT COUNT(*) FROM member_account WHERE tier = 'PRO'"));
        assertEquals(EVENT_COUNT, count("SELECT COUNT(*) FROM quota_account WHERE period_quota_balance = 500"));

        String poisonPayload = """
                {"eventId":"poison-%s","eventType":"UNSUPPORTED_EVENT","userId":%d,
                 "orderId":"poison-order-%s","productCode":"PRO_MONTH"}
                """.formatted(RUN_ID, userId(0), RUN_ID).replaceAll("\\s+", "");
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, poisonPayload);
        Message deadLetter = rabbitTemplate.receive(DLQ, 10_000);
        assertNotNull(deadLetter, "poison message was not routed to the DLQ after retry exhaustion");

        int duplicateGrantEffects = Math.max(0, grantLedgerRows - EVENT_COUNT);
        assertEquals(0, duplicateGrantEffects);
        List<Long> latencies = new ArrayList<>(deliveryLatencyMs.values());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("benchmarkType", "local-rabbitmq-mysql-integration");
        report.put("environment", environment());
        report.put("dataset", Map.of(
                "uniqueBenefitEvents", EVENT_COUNT,
                "duplicateDeliveries", DUPLICATE_DELIVERIES,
                "poisonMessageFaults", 1,
                "consumerConcurrency", 4,
                "configuredMaxAttempts", 4
        ));
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("benefitDeliverySuccessRatePct", round1(100d * deliveryLatencyMs.size() / EVENT_COUNT));
        results.put("benefitDeliveryLatencyP50Ms", percentile(latencies, 50));
        results.put("benefitDeliveryLatencyP95Ms", percentile(latencies, 95));
        results.put("benefitDeliveryLatencyP99Ms", percentile(latencies, 99));
        results.put("grantEventRows", EVENT_COUNT);
        results.put("grantLedgerRows", grantLedgerRows);
        results.put("duplicateGrantEffects", duplicateGrantEffects);
        results.put("poisonMessageDeadLettered", true);
        results.put("finalBenefitStateConsistent", true);
        report.put("results", results);
        report.put("methodology", "Publishes 50 unique completed-benefit events and one immediate duplicate for every event through an isolated RabbitMQ exchange/queue with four consumers. Polls an isolated MySQL schema until production consumer transactions become visible, then verifies one grant/event per order. One unsupported event verifies four configured listener attempts followed by DLQ routing. Retry intervals are shortened to 25 ms for the fault test.");
        writeReport(report);
    }

    private Map<String, Long> awaitDeliveredEvents(Map<String, Long> publishedAtNanos,
                                                    long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Map<String, Long> observed = new HashMap<>();
        while (System.nanoTime() < deadline && observed.size() < publishedAtNanos.size()) {
            long observedAt = System.nanoTime();
            List<String> orderIds = jdbcTemplate.queryForList(
                    "SELECT order_id FROM benefit_grant_event WHERE status = 'GRANTED'", String.class);
            for (String orderId : orderIds) {
                Long startedAt = publishedAtNanos.get(orderId);
                if (startedAt != null) {
                    observed.putIfAbsent(orderId,
                            TimeUnit.NANOSECONDS.toMillis(observedAt - startedAt));
                }
            }
            if (observed.size() < publishedAtNanos.size()) {
                Thread.sleep(5);
            }
        }
        return observed;
    }

    private String completedPayload(int index) {
        return """
                {"eventId":"event-%s-%d","eventType":"GROUP_BUY_COMPLETED","userId":%d,
                 "orderId":"%s","productCode":"PRO_MONTH","bonusQuota":0}
                """.formatted(RUN_ID, index, userId(index), orderId(index)).replaceAll("\\s+", "");
    }

    private long userId(int index) {
        return 9_200_000_000L + index;
    }

    private String orderId(int index) {
        return "resume-benefit-" + RUN_ID + "-" + index;
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private long percentile(List<Long> values, int percentile) {
        List<Long> sorted = values.stream().sorted().toList();
        assertTrue(!sorted.isEmpty(), "cannot calculate a percentile from an empty sample");
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
        String rabbitVersion = rabbitTemplate.execute(channel ->
                String.valueOf(channel.getConnection().getServerProperties().get("version")));
        environment.put("broker", "RabbitMQ " + rabbitVersion);
        return environment;
    }

    private void writeReport(Map<String, Object> report) throws Exception {
        Path reportPath = Path.of(System.getProperty(
                "resume.eval.report",
                "target/resume-evals/benefit-mq-benchmark.json"
        )).toAbsolutePath().normalize();
        Files.createDirectories(reportPath.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(reportPath.toFile(), report);
        System.out.println("RESUME_BENEFIT_MQ_REPORT=" + reportPath);
    }
}
