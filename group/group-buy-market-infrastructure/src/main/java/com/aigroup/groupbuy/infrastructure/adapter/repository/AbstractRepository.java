package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.infrastructure.dcc.DCCService;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Resource;
import java.util.function.Supplier;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 浠撳偍鎶借薄绫?
 */
public abstract class AbstractRepository {

    private final Logger logger = LoggerFactory.getLogger(AbstractRepository.class);

    @Resource
    protected IRedisService redisService;
    
    @Resource
    protected DCCService dccService;

    /**
     * 閫氱敤缂撳瓨澶勭悊鏂规硶
     * 浼樺厛浠庣紦瀛樿幏鍙栵紝缂撳瓨涓嶅瓨鍦ㄥ垯浠庢暟鎹簱鑾峰彇骞跺啓鍏ョ紦瀛?
     *
     * @param cacheKey      缂撳瓨閿?
     * @param dbFallback    鏁版嵁搴撴煡璇㈠嚱鏁?
     * @param <T>           杩斿洖绫诲瀷
     * @return              鏌ヨ缁撴灉
     */
    protected <T> T getFromCacheOrDb(String cacheKey, Supplier<T> dbFallback) {
        // 鍒ゆ柇鏄惁寮?鍚紦瀛?
        if (dccService.isCacheOpenSwitch()) {
            // 浠庣紦瀛樿幏鍙?
            T cacheResult = redisService.getValue(cacheKey);
            // 缂撳瓨瀛樺湪鍒欑洿鎺ヨ繑鍥?
            if (null != cacheResult) {
                return cacheResult;
            }
            // 缂撳瓨涓嶅瓨鍦ㄥ垯浠庢暟鎹簱鑾峰彇
            T dbResult = dbFallback.get();
            // 鏁版嵁搴撴煡璇㈢粨鏋滀负绌哄垯鐩存帴杩斿洖
            if (null == dbResult) {
                return null;
            }
            // 鍐欏叆缂撳瓨
            redisService.setValue(cacheKey, dbResult);
            return dbResult;
        } else {
            // 缂撳瓨鏈紑鍚紝鐩存帴浠庢暟鎹簱鑾峰彇
            logger.warn("缂撳瓨闄嶇骇 {}", cacheKey);
            return dbFallback.get();
        }
    }

    /**
     * 閫氱敤缂撳瓨澶勭悊鏂规硶锛堝甫杩囨湡鏃堕棿锛?
     * 浼樺厛浠庣紦瀛樿幏鍙栵紝缂撳瓨涓嶅瓨鍦ㄥ垯浠庢暟鎹簱鑾峰彇骞跺啓鍏ョ紦瀛?
     *
     * @param cacheKey      缂撳瓨閿?
     * @param dbFallback    鏁版嵁搴撴煡璇㈠嚱鏁?
     * @param expired       杩囨湡鏃堕棿
     * @param <T>           杩斿洖绫诲瀷
     * @return              鏌ヨ缁撴灉
     */
    protected <T> T getFromCacheOrDb(String cacheKey, Supplier<T> dbFallback, long expired) {
        // 鍒ゆ柇鏄惁寮?鍚紦瀛?
        if (dccService.isCacheOpenSwitch()) {
            // 浠庣紦瀛樿幏鍙?
            T cacheResult = redisService.getValue(cacheKey);
            // 缂撳瓨瀛樺湪鍒欑洿鎺ヨ繑鍥?
            if (null != cacheResult) {
                return cacheResult;
            }
            // 缂撳瓨涓嶅瓨鍦ㄥ垯浠庢暟鎹簱鑾峰彇
            T dbResult = dbFallback.get();
            // 鏁版嵁搴撴煡璇㈢粨鏋滀负绌哄垯鐩存帴杩斿洖
            if (null == dbResult) {
                return null;
            }
            // 鍐欏叆缂撳瓨锛堝甫杩囨湡鏃堕棿锛?
            redisService.setValue(cacheKey, dbResult, expired);
            return dbResult;
        } else {
            // 缂撳瓨鏈紑鍚紝鐩存帴浠庢暟鎹簱鑾峰彇
            logger.warn("缂撳瓨闄嶇骇 {}", cacheKey);
            return dbFallback.get();
        }
    }

}
