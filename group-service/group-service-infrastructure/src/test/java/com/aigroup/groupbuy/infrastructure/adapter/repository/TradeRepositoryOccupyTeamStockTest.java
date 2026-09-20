package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradeRepositoryOccupyTeamStockTest {
    private static final String STOCK = "group_buy_market_team_stock_key_100201_team-1";
    private static final String RECOVERY = STOCK + "_recovery";


    private TradeRepository repository;
    private IRedisService redisService;

    @Before
    public void setUp() {
        repository = new TradeRepository();
        redisService = mock(IRedisService.class);
        ReflectionTestUtils.setField(repository, "redisService", redisService);
    }

    @Test
    public void occupyUsesAtomicCapacityCheckWithoutResettingConcurrentCounter() {
        when(redisService.evalLong(anyString(), eq(List.of("stock", "rec")),
                eq(List.of("3", "90000")))).thenReturn(1L, 0L);

        assertTrue(repository.occupyTeamStock("stock", "rec", 3, 1440));
        assertFalse(repository.occupyTeamStock("stock", "rec", 3, 1440));
        verify(redisService, never()).setAtomicLong(eq("stock"), eq(3L));
        verify(redisService, never()).incr("stock");
    }

    @Test
    public void singleSeatTeamCannotAdmitAJoiner() {
        assertFalse(repository.occupyTeamStock("stock", "rec", 1, 30));
        verify(redisService, never()).evalLong(anyString(), eq(List.of("stock", "rec")), eq(List.of("1", "5400")));
    }

    @Test
    public void recoveryKeepsAtLeastTheExistingStockTtl() {
        repository.recoveryTeamStock(null, 30);
        repository.recoveryTeamStock(RECOVERY, 30);
        verify(redisService).evalLong(org.mockito.ArgumentMatchers.argThat(script ->
                        script.contains("'INCR'") && script.contains("'EXPIRE'")
                                && script.contains("math.max(tonumber(ARGV[1]), redis.call('TTL', KEYS[2]))")
                                && script.contains("redis.call('TTL', KEYS[1]) < ttl")),
                eq(List.of(RECOVERY, STOCK)), eq(List.of("5400")));
        assertThrows(IllegalArgumentException.class, () -> repository.recoveryTeamStock("wrong-key", 30));
    }

    @Test
    public void refundRecoveryIsIdempotentPerOrderId() {
        when(redisService.setNx(eq("refund_lock_ord-1"), eq(30L), eq(TimeUnit.DAYS))).thenReturn(true);
        repository.refund2AddRecovery(RECOVERY, "ord-1");
        verify(redisService).evalLong(anyString(), eq(List.of(RECOVERY, STOCK)), eq(List.of("2592000")));

        when(redisService.setNx(eq("refund_lock_ord-1"), eq(30L), eq(TimeUnit.DAYS))).thenReturn(false);
        repository.refund2AddRecovery(RECOVERY, "ord-1");
        verify(redisService).evalLong(anyString(), eq(List.of(RECOVERY, STOCK)), eq(List.of("2592000")));
    }
}
