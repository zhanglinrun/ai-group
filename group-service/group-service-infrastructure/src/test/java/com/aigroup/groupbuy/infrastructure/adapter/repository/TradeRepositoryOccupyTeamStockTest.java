package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradeRepositoryOccupyTeamStockTest {

    private TradeRepository repository;
    private IRedisService redisService;

    @Before
    public void setUp() {
        repository = new TradeRepository();
        redisService = mock(IRedisService.class);
        ReflectionTestUtils.setField(repository, "redisService", redisService);
    }

    @Test
    public void occupyUsesIncrPlusOneAgainstTargetAndRecovery() {
        when(redisService.getAtomicLong("rec")).thenReturn(0L);
        when(redisService.incr("stock")).thenReturn(1L);
        when(redisService.setNx(eq("stock_2"), eq(1500L), eq(TimeUnit.MINUTES))).thenReturn(true);

        assertTrue(repository.occupyTeamStock("stock", "rec", 3, 1440));
        verify(redisService, never()).decr("stock");
    }

    @Test
    public void occupyRollsBackIncrWhenOverTargetPlusRecovery() {
        when(redisService.getAtomicLong("rec")).thenReturn(0L);
        when(redisService.incr("stock")).thenReturn(3L);

        assertFalse(repository.occupyTeamStock("stock", "rec", 3, 1440));
        verify(redisService).decr("stock");
        verify(redisService, never()).setNx(any(), anyLong(), any());
    }

    @Test
    public void recoverySkipsBlankKeyAndIncrementsOtherwise() {
        repository.recoveryTeamStock(null, 30);
        repository.recoveryTeamStock("rec", 30);
        verify(redisService).incr("rec");
    }

    @Test
    public void refundRecoveryIsIdempotentPerOrderId() {
        when(redisService.setNx(eq("refund_lock_ord-1"), anyLong(), eq(TimeUnit.MINUTES))).thenReturn(true);
        repository.refund2AddRecovery("rec", "ord-1");
        verify(redisService).incr("rec");

        when(redisService.setNx(eq("refund_lock_ord-1"), anyLong(), eq(TimeUnit.MINUTES))).thenReturn(false);
        repository.refund2AddRecovery("rec", "ord-1");
        verify(redisService).incr("rec");
    }
}
