package com.aigroup.groupbuy.infrastructure.cache;

import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivity;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage aligned with campus-dash Cache Aside behaviors:
 * shard write, null cache, TTL jitter, Single-Flight miss coalesce, delayed double-delete.
 */
public class MarketConfigCacheSupportTest {

    private IRedisService redisService;
    private MarketConfigCacheSupport support;

    @Before
    public void setUp() {
        redisService = mock(IRedisService.class);
        // jitter=0 so put assertions stay exact
        support = new MarketConfigCacheSupport(redisService, 4, 600_000L, 0L, 60_000L, 0L);
    }

    @Test
    public void putWritesAllShardsWithTtlAndClearsLegacyKey() {
        GroupBuyActivity activity = GroupBuyActivity.builder().activityId(100201L).activityName("a").build();

        support.put("cfg:100201", activity);

        verify(redisService).remove("cfg:100201");
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisService, atLeast(4)).setValue(keys.capture(), eq(activity), eq(600_000L));
        Set<String> shardKeys = new HashSet<>(keys.getAllValues());
        assertTrue(shardKeys.contains("cfg:100201:s0"));
        assertTrue(shardKeys.contains("cfg:100201:s1"));
        assertTrue(shardKeys.contains("cfg:100201:s2"));
        assertTrue(shardKeys.contains("cfg:100201:s3"));
    }

    @Test
    public void putAppliesTtlJitterWithinBounds() {
        MarketConfigCacheSupport jittered =
                new MarketConfigCacheSupport(redisService, 4, 600_000L, 120_000L, 60_000L, 0L);
        GroupBuyActivity activity = GroupBuyActivity.builder().activityId(1L).build();

        jittered.put("cfg:jitter", activity);

        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(redisService, atLeast(4)).setValue(anyString(), eq(activity), ttl.capture());
        for (Long value : ttl.getAllValues()) {
            assertTrue(value >= 480_000L && value <= 720_000L);
        }
    }

    @Test
    public void putNullUsesShortTtlMarker() {
        support.putNull("cfg:missing");

        verify(redisService).remove("cfg:missing");
        verify(redisService, atLeast(4)).setValue(anyString(), eq(MarketConfigCacheSupport.NULL_MARKER), eq(60_000L));
    }

    @Test
    public void getReturnsNullCachedForMarker() {
        when(redisService.getValue(anyString())).thenReturn(MarketConfigCacheSupport.NULL_MARKER);

        MarketConfigCacheSupport.LookupResult<GroupBuyActivity> result = support.get("cfg:x");

        assertTrue(result.isNullCached());
    }

    @Test
    public void getReturnsHitFromShard() {
        GroupBuyActivity activity = GroupBuyActivity.builder().activityId(1L).build();
        when(redisService.getValue(anyString())).thenReturn(activity);

        MarketConfigCacheSupport.LookupResult<GroupBuyActivity> result = support.get("cfg:1");

        assertTrue(result.isHit());
        assertEquals(Long.valueOf(1L), result.value().getActivityId());
    }

    @Test
    public void getFallsBackToLegacyUnshardedKey() {
        GroupBuyActivity activity = GroupBuyActivity.builder().activityId(2L).build();
        when(redisService.getValue(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.contains(":s")) {
                return null;
            }
            return activity;
        });

        MarketConfigCacheSupport.LookupResult<GroupBuyActivity> result = support.get("cfg:legacy");

        assertTrue(result.isHit());
        assertEquals(Long.valueOf(2L), result.value().getActivityId());
    }

    @Test
    public void loadThroughMissesOnceThenHitsWithoutSecondDbLoad() {
        AtomicInteger dbLoads = new AtomicInteger();
        when(redisService.getValue(anyString())).thenReturn(null);
        GroupBuyActivity activity = GroupBuyActivity.builder().activityId(9L).build();

        GroupBuyActivity first = support.loadThrough("cfg:sf", () -> {
            dbLoads.incrementAndGet();
            return activity;
        }, null);
        when(redisService.getValue(anyString())).thenReturn(activity);
        GroupBuyActivity second = support.loadThrough("cfg:sf", () -> {
            dbLoads.incrementAndGet();
            return activity;
        }, null);

        assertEquals(Long.valueOf(9L), first.getActivityId());
        assertEquals(Long.valueOf(9L), second.getActivityId());
        assertEquals(1, dbLoads.get());
    }

    @Test
    public void loadThroughSingleFlightCoalescesConcurrentMisses() throws Exception {
        AtomicInteger dbLoads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redisService.getValue(anyString())).thenReturn(null);
        GroupBuyActivity activity = GroupBuyActivity.builder().activityId(7L).build();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> support.loadThrough("cfg:coalesce", () -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    dbLoads.incrementAndGet();
                    return activity;
                }, null));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, dbLoads.get());
    }

    @Test
    public void evictNowAndDelayedWithZeroDelayDeletesTwice() {
        support.evictNowAndDelayed("cfg:evict");

        verify(redisService, atLeast(10)).remove(anyString());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void evictNowAndDelayedSchedulesSecondDelete() throws Exception {
        RBlockingQueue blocking = mock(RBlockingQueue.class);
        RDelayedQueue delayed = mock(RDelayedQueue.class);
        when(redisService.getBlockingQueue(anyString())).thenReturn(blocking);
        when(redisService.getDelayedQueue(blocking)).thenReturn(delayed);
        when(blocking.poll(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(null);

        MarketConfigCacheSupport withDelay =
                new MarketConfigCacheSupport(redisService, 4, 600_000L, 0L, 60_000L, 500L);
        withDelay.startDelayedEvictConsumer();
        try {
            withDelay.evictNowAndDelayed("cfg:dd");
            verify(delayed, times(1)).offer("cfg:dd", 500L, TimeUnit.MILLISECONDS);
        } finally {
            withDelay.stopDelayedEvictConsumer();
        }
    }

    @Test
    public void putIgnoresBlankKey() {
        support.put("  ", GroupBuyActivity.builder().build());
        verify(redisService, never()).setValue(anyString(), org.mockito.ArgumentMatchers.any(), anyLong());
    }
}
