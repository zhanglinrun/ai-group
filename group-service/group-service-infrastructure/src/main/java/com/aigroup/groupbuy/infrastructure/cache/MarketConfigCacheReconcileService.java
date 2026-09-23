package com.aigroup.groupbuy.infrastructure.cache;

import com.aigroup.groupbuy.domain.activity.service.IMarketConfigCacheReconcileService;
import com.aigroup.groupbuy.infrastructure.adapter.repository.ActivityRepository;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyActivityDao;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyDiscountDao;
import com.aigroup.groupbuy.infrastructure.dao.ISCSkuActivityDao;
import com.aigroup.groupbuy.infrastructure.dao.ISkuDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivity;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyDiscount;
import com.aigroup.groupbuy.infrastructure.dao.po.SCSkuActivity;
import com.aigroup.groupbuy.infrastructure.dao.po.Sku;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Samples marketing-config Redis shards against MySQL and evicts mismatches.
 * Does not rewrite DB; cache is disposable.
 */
@Slf4j
@Service
public class MarketConfigCacheReconcileService implements IMarketConfigCacheReconcileService {

    private final IGroupBuyActivityDao activityDao;
    private final IGroupBuyDiscountDao discountDao;
    private final ISkuDao skuDao;
    private final ISCSkuActivityDao scSkuActivityDao;
    private final MarketConfigCacheSupport cacheSupport;
    private final int sampleSize;

    public MarketConfigCacheReconcileService(
            IGroupBuyActivityDao activityDao,
            IGroupBuyDiscountDao discountDao,
            ISkuDao skuDao,
            ISCSkuActivityDao scSkuActivityDao,
            MarketConfigCacheSupport cacheSupport,
            @Value("${group.market-config.reconcile.sample-size:100}") int sampleSize) {
        this.activityDao = activityDao;
        this.discountDao = discountDao;
        this.skuDao = skuDao;
        this.scSkuActivityDao = scSkuActivityDao;
        this.cacheSupport = cacheSupport;
        this.sampleSize = Math.max(1, sampleSize);
    }

    @Override
    public int reconcile() {
        List<GroupBuyActivity> all = activityDao.queryGroupBuyActivityList();
        if (all == null || all.isEmpty()) {
            return 0;
        }
        List<GroupBuyActivity> sample = new ArrayList<>(all);
        Collections.shuffle(sample);
        if (sample.size() > sampleSize) {
            sample = sample.subList(0, sampleSize);
        }

        int mismatches = 0;
        for (GroupBuyActivity db : sample) {
            if (db == null || db.getActivityId() == null) {
                continue;
            }
            mismatches += checkActivity(db);
            mismatches += checkDiscount(db.getDiscountId());
            mismatches += checkSku(db.getActivityId());
            mismatches += checkScSkuActivity(db.getActivityId());
        }
        if (mismatches > 0) {
            log.warn("market config cache reconcile mismatches={}", mismatches);
        }
        return mismatches;
    }

    private int checkActivity(GroupBuyActivity db) {
        String key = GroupBuyActivity.cacheRedisKey(db.getActivityId());
        Object cached = cacheSupport.peekAnyShard(key);
        if (cached == null) {
            return 0;
        }
        if (MarketConfigCacheSupport.isNullMarker(cached)) {
            evictLogical(key, ActivityRepository.activityDiscountCacheKey(db.getActivityId()));
            return 1;
        }
        if (!(cached instanceof GroupBuyActivity cachedActivity)
                || !activityMatches(db, cachedActivity)) {
            evictLogical(key, ActivityRepository.activityDiscountCacheKey(db.getActivityId()));
            return 1;
        }
        return 0;
    }

    private int checkDiscount(String discountId) {
        if (discountId == null) {
            return 0;
        }
        String key = GroupBuyDiscount.cacheRedisKey(discountId);
        Object cached = cacheSupport.peekAnyShard(key);
        if (cached == null) {
            return 0;
        }
        GroupBuyDiscount db = discountDao.queryGroupBuyActivityDiscountByDiscountId(discountId);
        if (db == null) {
            if (!MarketConfigCacheSupport.isNullMarker(cached)) {
                evictLogical(key);
                return 1;
            }
            return 0;
        }
        if (MarketConfigCacheSupport.isNullMarker(cached)
                || !(cached instanceof GroupBuyDiscount cachedDiscount)
                || !discountMatches(db, cachedDiscount)) {
            evictLogical(key);
            return 1;
        }
        return 0;
    }

    private int checkSku(Long activityId) {
        SCSkuActivity mapping = findMapping(activityId);
        if (mapping == null || mapping.getGoodsId() == null) {
            return 0;
        }
        String key = Sku.cacheRedisKey(mapping.getGoodsId());
        Object cached = cacheSupport.peekAnyShard(key);
        if (cached == null) {
            return 0;
        }
        Sku db = skuDao.querySkuByGoodsId(mapping.getGoodsId());
        if (db == null) {
            if (!MarketConfigCacheSupport.isNullMarker(cached)) {
                evictLogical(key);
                return 1;
            }
            return 0;
        }
        if (MarketConfigCacheSupport.isNullMarker(cached)
                || !(cached instanceof Sku cachedSku)
                || !skuMatches(db, cachedSku)) {
            evictLogical(key);
            return 1;
        }
        return 0;
    }

    private int checkScSkuActivity(Long activityId) {
        SCSkuActivity mapping = findMapping(activityId);
        if (mapping == null || mapping.getSource() == null || mapping.getChannel() == null
                || mapping.getGoodsId() == null) {
            return 0;
        }
        String key = SCSkuActivity.cacheRedisKey(mapping.getSource(), mapping.getChannel(), mapping.getGoodsId());
        Object cached = cacheSupport.peekAnyShard(key);
        if (cached == null) {
            return 0;
        }
        if (MarketConfigCacheSupport.isNullMarker(cached)
                || !(cached instanceof SCSkuActivity cachedMapping)
                || !scMatches(mapping, cachedMapping)) {
            evictLogical(key);
            return 1;
        }
        return 0;
    }

    private SCSkuActivity findMapping(Long activityId) {
        List<SCSkuActivity> mappings = scSkuActivityDao.querySCSkuActivityList();
        if (mappings == null) {
            return null;
        }
        for (SCSkuActivity mapping : mappings) {
            if (mapping != null && Objects.equals(activityId, mapping.getActivityId())) {
                return mapping;
            }
        }
        return null;
    }

    private void evictLogical(String... keys) {
        for (String key : keys) {
            cacheSupport.evict(key);
        }
    }

    private static boolean activityMatches(GroupBuyActivity db, GroupBuyActivity cached) {
        return Objects.equals(db.getActivityId(), cached.getActivityId())
                && Objects.equals(db.getActivityName(), cached.getActivityName())
                && Objects.equals(db.getDiscountId(), cached.getDiscountId())
                && Objects.equals(db.getGroupType(), cached.getGroupType())
                && Objects.equals(db.getTakeLimitCount(), cached.getTakeLimitCount())
                && Objects.equals(db.getTarget(), cached.getTarget())
                && Objects.equals(db.getValidTime(), cached.getValidTime())
                && Objects.equals(db.getStatus(), cached.getStatus());
    }

    private static boolean discountMatches(GroupBuyDiscount db, GroupBuyDiscount cached) {
        return Objects.equals(db.getDiscountId(), cached.getDiscountId())
                && Objects.equals(db.getDiscountType(), cached.getDiscountType())
                && Objects.equals(db.getMarketPlan(), cached.getMarketPlan())
                && Objects.equals(db.getMarketExpr(), cached.getMarketExpr())
                && Objects.equals(db.getTagId(), cached.getTagId());
    }

    private static boolean skuMatches(Sku db, Sku cached) {
        return Objects.equals(db.getGoodsId(), cached.getGoodsId())
                && Objects.equals(db.getGoodsName(), cached.getGoodsName())
                && Objects.equals(db.getOriginalPrice(), cached.getOriginalPrice());
    }

    private static boolean scMatches(SCSkuActivity db, SCSkuActivity cached) {
        return Objects.equals(db.getSource(), cached.getSource())
                && Objects.equals(db.getChannel(), cached.getChannel())
                && Objects.equals(db.getGoodsId(), cached.getGoodsId())
                && Objects.equals(db.getActivityId(), cached.getActivityId());
    }
}
