package com.aigroup.groupbuy.infrastructure.cache;

import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyActivityDao;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyDiscountDao;
import com.aigroup.groupbuy.infrastructure.dao.ISCSkuActivityDao;
import com.aigroup.groupbuy.infrastructure.dao.ISkuDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivity;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MarketConfigCacheReconcileServiceTest {

    private IGroupBuyActivityDao activityDao;
    private IGroupBuyDiscountDao discountDao;
    private ISkuDao skuDao;
    private ISCSkuActivityDao scSkuActivityDao;
    private MarketConfigCacheSupport cacheSupport;
    private MarketConfigCacheReconcileService service;

    @Before
    public void setUp() {
        activityDao = mock(IGroupBuyActivityDao.class);
        discountDao = mock(IGroupBuyDiscountDao.class);
        skuDao = mock(ISkuDao.class);
        scSkuActivityDao = mock(ISCSkuActivityDao.class);
        cacheSupport = mock(MarketConfigCacheSupport.class);
        service = new MarketConfigCacheReconcileService(
                activityDao, discountDao, skuDao, scSkuActivityDao, cacheSupport, 100);
    }

    @Test
    public void mismatchedActivityCacheIsEvicted() {
        GroupBuyActivity db = GroupBuyActivity.builder()
                .activityId(100201L)
                .activityName("new")
                .discountId("d1")
                .groupType(1)
                .takeLimitCount(1)
                .target(3)
                .validTime(30)
                .status(1)
                .build();
        GroupBuyActivity stale = GroupBuyActivity.builder()
                .activityId(100201L)
                .activityName("old")
                .discountId("d1")
                .groupType(1)
                .takeLimitCount(1)
                .target(3)
                .validTime(30)
                .status(1)
                .build();
        when(activityDao.queryGroupBuyActivityList()).thenReturn(List.of(db));
        when(cacheSupport.peekAnyShard(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.equals(GroupBuyActivity.cacheRedisKey(100201L))) {
                return stale;
            }
            return null;
        });
        when(scSkuActivityDao.querySCSkuActivityList()).thenReturn(Collections.emptyList());

        int mismatches = service.reconcile();

        assertEquals(1, mismatches);
        verify(cacheSupport, atLeastOnce()).evict(GroupBuyActivity.cacheRedisKey(100201L));
    }

    @Test
    public void missIsNotCountedAsMismatch() {
        GroupBuyActivity db = GroupBuyActivity.builder().activityId(1L).discountId("d").build();
        when(activityDao.queryGroupBuyActivityList()).thenReturn(List.of(db));
        when(cacheSupport.peekAnyShard(anyString())).thenReturn(null);
        when(scSkuActivityDao.querySCSkuActivityList()).thenReturn(Collections.emptyList());

        assertEquals(0, service.reconcile());
    }
}
