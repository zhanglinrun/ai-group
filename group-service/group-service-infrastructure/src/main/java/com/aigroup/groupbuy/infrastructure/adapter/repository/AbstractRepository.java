package com.aigroup.groupbuy.infrastructure.adapter.repository;

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

    /**
     * 通用缓存处理方法
     * 优先从缓存获取，缓存不存在则从数据库获取并写入缓存
     *
     * @param cacheKey      缓存键
     * @param dbFallback    数据库查询函数
     * @param <T>           返回类型
     * @return              查询结果
     */
    protected <T> T getFromCacheOrDb(String cacheKey, Supplier<T> dbFallback) {
        // 判断是否开启缓存
        if (dccService.isCacheOpenSwitch()) {
            // 从缓存获取
            T cacheResult = redisService.getValue(cacheKey);
            // 缓存存在则直接返回
            if (null != cacheResult) {
                return cacheResult;
            }
            // 缓存不存在则从数据库获取
            T dbResult = dbFallback.get();
            // 数据库查询结果为空则直接返回
            if (null == dbResult) {
                return null;
            }
            // 写入缓存
            redisService.setValue(cacheKey, dbResult);
            return dbResult;
        } else {
            // 缓存未开启，直接从数据库获取
            logger.warn("缓存降级 {}", cacheKey);
            return dbFallback.get();
        }
    }

}
