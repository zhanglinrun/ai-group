package com.aigroup.groupbuy.infrastructure.cache;

import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class HallDetailCacheEvictSupportTest {

    private MarketConfigCacheSupport cacheSupport;
    private HallDetailCacheEvictSupport evictSupport;

    @Before
    public void setUp() {
        cacheSupport = mock(MarketConfigCacheSupport.class);
        evictSupport = new HallDetailCacheEvictSupport(cacheSupport);
    }

    @Test
    public void doEvictIncludesOwnerKeyWhenUserPresent() {
        evictSupport.doEvict(100201L, "u9");
        verify(cacheSupport, times(1)).evictNowAndDelayed(
                eq(HallDetailCacheKeys.teamStatistic(100201L)),
                eq(HallDetailCacheKeys.progressPool(100201L)),
                eq(HallDetailCacheKeys.ownerTeams(100201L, "u9")));
    }

    @Test
    public void doEvictSkipsOwnerKeyWhenUserBlank() {
        evictSupport.doEvict(100201L, null);
        verify(cacheSupport, times(1)).evictNowAndDelayed(
                eq(HallDetailCacheKeys.teamStatistic(100201L)),
                eq(HallDetailCacheKeys.progressPool(100201L)));
    }
}
