package com.aigroup.groupbuy.infrastructure.redis;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamStockOccupyScriptTest {

    @Test
    public void luaKeepsIncrPlusOneAndRollback() {
        assertTrue(TeamStockOccupyScript.LUA.contains("redis.call('INCR', KEYS[1]) + 1"));
        assertTrue(TeamStockOccupyScript.LUA.contains("redis.call('DECR', KEYS[1])"));
        assertTrue(TeamStockOccupyScript.LUA.contains("SET"));
        assertTrue(TeamStockOccupyScript.LUA.contains("NX"));
    }

    @Test
    public void occupyReturnsTrueWhenScriptAllowsSeat() {
        IRedisService redis = mock(IRedisService.class);
        when(redis.evalLong(eq(TeamStockOccupyScript.LUA), eq(List.of("team:1", "team:1:recovery")),
                eq(List.of("20", String.valueOf(90 * 60))))).thenReturn(1L);

        assertTrue(TeamStockOccupyScript.occupy(redis, "team:1", "team:1:recovery", 20, 30));
    }

    @Test
    public void occupyReturnsFalseOnOversellOrSlotCollision() {
        IRedisService redis = mock(IRedisService.class);
        when(redis.evalLong(eq(TeamStockOccupyScript.LUA), eq(List.of("team:1", "team:1:recovery")),
                eq(List.of("20", String.valueOf(90 * 60))))).thenReturn(0L);

        assertFalse(TeamStockOccupyScript.occupy(redis, "team:1", "team:1:recovery", 20, 30));
    }

    @Test
    public void occupyPassesTargetAndExtendedLockTtl() {
        IRedisService redis = mock(IRedisService.class);
        AtomicReference<List<String>> args = new AtomicReference<>();
        when(redis.evalLong(eq(TeamStockOccupyScript.LUA), eq(List.of("k", "r")),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            args.set(invocation.getArgument(2));
            return 1L;
        });

        assertTrue(TeamStockOccupyScript.occupy(redis, "k", "r", 8, 15));
        assertEquals("8", args.get().get(0));
        assertEquals(String.valueOf(75 * 60), args.get().get(1));
        verify(redis).evalLong(eq(TeamStockOccupyScript.LUA), eq(List.of("k", "r")), eq(args.get()));
    }
}
