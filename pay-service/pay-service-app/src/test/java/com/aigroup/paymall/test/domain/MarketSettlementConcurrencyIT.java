package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.order.model.entity.TeamSettlementMember;
import com.aigroup.paymall.domain.order.service.IOrderService;
import jakarta.annotation.Resource;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Campus-dash SettleConcurrencyIT-shaped slice: in-process concurrent
 * {@link IOrderService#changeOrderMarketSettlement} against real MySQL.
 * <p>
 * Measures PAY_SUCCESS→MARKET CAS + benefit outbox insert (txn commit), not
 * Member grant / full Kafka E2E. Skips when bench MySQL is unreachable.
 * <p>
 * Run against ai-group-bench (port 13306), with MYSQL_ROOT_PASSWORD in the env:
 * {@code mvn -pl pay-service/pay-service-app -Dtest=MarketSettlementConcurrencyIT test}
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/s_pay_mall_ddd_market?useUnicode=true&characterEncoding=utf8&autoReconnect=true&zeroDateTimeBehavior=convertToNull&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=${MYSQL_ROOT_PASSWORD:}",
        "spring.datasource.hikari.maximum-pool-size=40",
        "thread.pool.executor.config.core-pool-size=32",
        "thread.pool.executor.config.max-pool-size=64",
        "thread.pool.executor.config.policy=AbortPolicy",
        "xxl.job.enabled=false",
        "alipay.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}"
})
public class MarketSettlementConcurrencyIT {

    private static final int COUNT = 500;
    private static final int WORKERS = 64;
    private static final long ACTIVITY_ID = 100201L;
    private static final String PRODUCT_ID = "9890002";
    private static final String PRODUCT_CODE = "QUOTA_LIGHT";
    private static final String PRODUCT_NAME = "QUOTA_LIGHT";
    private static final int BASE_QUOTA = 60;
    private static final String SOURCE = "s01";
    private static final String CHANNEL = "c01";
    private static final String PREFIX = "it-mkt-" + System.currentTimeMillis();

    @Resource
    private IOrderService orderService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeClass
    public static void requireBenchMysql() {
        String password = System.getenv("MYSQL_ROOT_PASSWORD");
        Assume.assumeTrue("MYSQL_ROOT_PASSWORD not set; skip Pay MARKET concurrency IT",
                password != null && !password.trim().isEmpty());
        String url = "jdbc:mysql://127.0.0.1:13306/s_pay_mall_ddd_market?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection ignored = DriverManager.getConnection(url, "root", password)) {
            // reachable
        } catch (Exception ex) {
            Assume.assumeTrue("bench MySQL on 13306 unreachable: " + ex.getMessage(), false);
        }
    }

    @Test
    public void concurrent_market_settlement_distinct_orders() throws Exception {
        seed(COUNT);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch done = new CountDownLatch(COUNT);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < COUNT; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    String userId = String.valueOf(9_000_005_000_000L + idx);
                    String orderId = PREFIX + "-o" + String.format("%04d", idx);
                    String teamId = PREFIX + "-t" + String.format("%04d", idx);
                    orderService.changeOrderMarketSettlement(
                            teamId,
                            ACTIVITY_ID,
                            Collections.singletonList(new TeamSettlementMember(userId, SOURCE, CHANNEL, orderId)));
                    settled.incrementAndGet();
                } catch (Exception ex) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue("500 MARKET settles should finish within 180s", done.await(180, TimeUnit.SECONDS));
        long elapsed = Math.max(System.currentTimeMillis() - t0, 1L);
        pool.shutdown();

        long tps = Math.round(COUNT * 1000.0 / elapsed);

        Integer market = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pay_order WHERE order_id LIKE ? AND status='MARKET'",
                Integer.class, PREFIX + "-o%");
        Integer outbox = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM benefit_event WHERE order_id LIKE ?",
                Integer.class, PREFIX + "-o%");

        System.out.printf(
                "[Pay MARKET slice] orders=%d workers=%d clientOk=%d failed=%d elapsedMs=%d payMarketTps≈%d market=%d outbox=%d%n",
                COUNT, WORKERS, settled.get(), failed.get(), elapsed, tps, market, outbox);

        assertEquals("zero duplicate MARKET rows", Integer.valueOf(COUNT), market);
        assertEquals("outbox rows must equal settled orders", Integer.valueOf(COUNT), outbox);
        assertEquals("client success count", COUNT, settled.get());
        assertEquals("zero failures", 0, failed.get());
    }

    private void seed(int count) {
        jdbcTemplate.update("DELETE FROM benefit_event WHERE order_id LIKE ?", PREFIX + "-o%");
        jdbcTemplate.update("DELETE FROM pay_order WHERE order_id LIKE ?", PREFIX + "-o%");
        for (int i = 0; i < count; i++) {
            String userId = String.valueOf(9_000_005_000_000L + i);
            String orderId = PREFIX + "-o" + String.format("%04d", i);
            String teamId = PREFIX + "-t" + String.format("%04d", i);
            jdbcTemplate.update(
                    "INSERT INTO pay_order("
                            + "client_request_id, request_fingerprint, create_stage, "
                            + "user_id, product_id, product_code, product_name, base_quota_snapshot, "
                            + "order_id, order_time, total_amount, status, pay_time, market_type, "
                            + "group_activity_id, group_team_id, group_source, group_channel, "
                            + "market_deduction_amount, pay_amount, settlement_notified"
                            + ") VALUES (?,?,?,?,?,?,?,?,?,NOW(),?,?,NOW(),?,?,?,?,?,?,?,?)",
                    "req-" + orderId, String.format("%064x", Math.abs((long) orderId.hashCode())), "PREPAY_READY",
                    userId, PRODUCT_ID, PRODUCT_CODE, PRODUCT_NAME, BASE_QUOTA,
                    orderId, 10.80, "PAY_SUCCESS", 1,
                    ACTIVITY_ID, teamId, SOURCE, CHANNEL,
                    1.20, 10.80, 0);
        }
    }
}
