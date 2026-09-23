package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.infrastructure.cache.MarketConfigCacheSupport;
import com.aigroup.groupbuy.infrastructure.cache.MarketConfigReadMetrics;
import com.aigroup.groupbuy.infrastructure.dcc.DCCService;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.function.Supplier;

/**
 * @description 仓储抽象类
 */
public abstract class AbstractRepository {

    private final Logger logger = LoggerFactory.getLogger(AbstractRepository.class);

    @Resource
    protected IRedisService redisService;

    @Resource
    protected DCCService dccService;

    @Resource
    protected MarketConfigCacheSupport marketConfigCacheSupport;

    @Resource
    protected MarketConfigReadMetrics marketConfigReadMetrics;

    /**
     * Redis-only Cache Aside (campus-dash style). No process-local layer.
     */
    protected <T> T getFromCacheOrDb(String cacheKey, Supplier<T> dbFallback) {
        return getFromCacheOrDb(cacheKey, dbFallback, -1L);
    }

    protected <T> T getFromCacheOrDb(String cacheKey, Supplier<T> dbFallback, long ttlMs) {
        if (!dccService.isCacheOpenSwitch()) {
            logger.debug("缓存降级 {}", cacheKey);
            marketConfigReadMetrics.recordDbLoad();
            return dbFallback.get();
        }
        return marketConfigCacheSupport.loadThrough(
                cacheKey, dbFallback, marketConfigReadMetrics::recordDbLoad, ttlMs);
    }

}
