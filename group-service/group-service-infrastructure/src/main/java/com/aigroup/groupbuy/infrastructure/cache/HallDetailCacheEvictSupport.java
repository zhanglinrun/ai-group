package com.aigroup.groupbuy.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * afterCommit hall-cache eviction + delayed double-delete (campus-dash CacheEvictSupport style).
 */
@Slf4j
@Component
public class HallDetailCacheEvictSupport {

    private final MarketConfigCacheSupport cacheSupport;

    public HallDetailCacheEvictSupport(MarketConfigCacheSupport cacheSupport) {
        this.cacheSupport = cacheSupport;
    }

    public void evictAfterCommit(Long activityId, String userId) {
        if (activityId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            doEvict(activityId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doEvict(activityId, userId);
            }
        });
    }

    void doEvict(Long activityId, String userId) {
        try {
            if (StringUtils.isNotBlank(userId)) {
                cacheSupport.evictNowAndDelayed(
                        HallDetailCacheKeys.teamStatistic(activityId),
                        HallDetailCacheKeys.progressPool(activityId),
                        HallDetailCacheKeys.ownerTeams(activityId, userId));
            } else {
                cacheSupport.evictNowAndDelayed(
                        HallDetailCacheKeys.teamStatistic(activityId),
                        HallDetailCacheKeys.progressPool(activityId));
            }
        } catch (RuntimeException ex) {
            log.error("hall cache evict failed activityId={} userId={}", activityId, userId, ex);
        }
    }
}
