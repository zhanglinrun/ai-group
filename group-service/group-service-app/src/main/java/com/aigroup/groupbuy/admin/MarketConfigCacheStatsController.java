package com.aigroup.groupbuy.admin;

import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.activity.adapter.repository.IActivityRepository;
import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.SCSkuActivityVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.SkuVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.TeamStatisticVO;
import com.aigroup.groupbuy.infrastructure.cache.MarketConfigCacheSupport;
import com.aigroup.groupbuy.infrastructure.cache.MarketConfigReadMetrics;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bench/diagnostics for hall+config cache accounting (campus-dash S3 style).
 */
@RestController
@RequestMapping("/api/v1/gbm/index/")
public class MarketConfigCacheStatsController {

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Resource
    private MarketConfigCacheSupport marketConfigCacheSupport;
    @Resource
    private MarketConfigReadMetrics marketConfigReadMetrics;
    @Resource
    private IActivityRepository activityRepository;

    @RequestMapping(value = "market_config_cache_stats", method = RequestMethod.GET)
    public Response<Map<String, Object>> stats(
            @RequestParam(value = "reset", defaultValue = "false") boolean reset,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        MarketConfigReadMetrics.Snapshot read = reset
                ? marketConfigReadMetrics.snapshotAndReset()
                : marketConfigReadMetrics.snapshot();
        MarketConfigCacheSupport.CacheStats redis = reset
                ? marketConfigCacheSupport.snapshotAndReset()
                : marketConfigCacheSupport.snapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestCount", read.requestCount());
        body.put("cacheHitCount", read.cacheHitCount());
        body.put("dbLoadCount", read.dbLoadCount());
        body.put("hitRate", read.hitRate());
        body.put("redisHits", redis.hits());
        body.put("redisMisses", redis.misses());
        body.put("redisNullHits", redis.nullHits());
        body.put("redisHitRate", redis.hitRate());
        body.put("shardCount", marketConfigCacheSupport.shardCount());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(body)
                .build();
    }

    /**
     * Hall-detail probe: marketing config + owner/random teams + activity stats.
     * One request; cache hit means zero MySQL loads in that request (campus-dash semantics).
     */
    @RequestMapping(value = "market_config_cache_probe", method = RequestMethod.GET)
    public Response<Map<String, Object>> probe(
            @RequestParam("goodsId") String goodsId,
            @RequestParam(value = "source", defaultValue = "s01") String source,
            @RequestParam(value = "channel", defaultValue = "c01") String channel,
            @RequestParam(value = "userId", defaultValue = "9100000000000") String userId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        marketConfigReadMetrics.recordRequest();
        long dbBefore = marketConfigReadMetrics.dbLoadCount();
        long started = System.nanoTime();
        SCSkuActivityVO mapping = activityRepository.querySCSkuActivityBySCGoodsId(source, channel, goodsId);
        SkuVO sku = activityRepository.querySkuByGoodsId(goodsId);
        GroupBuyActivityDiscountVO activity = mapping == null || mapping.getActivityId() == null
                ? null
                : activityRepository.queryGroupBuyActivityDiscountVO(mapping.getActivityId());
        Long activityId = mapping == null ? null : mapping.getActivityId();
        List<UserGroupBuyOrderDetailEntity> ownerTeams = null;
        List<UserGroupBuyOrderDetailEntity> randomTeams = null;
        TeamStatisticVO teamStatistic = null;
        if (activityId != null) {
            ownerTeams = activityRepository.queryInProgressUserGroupBuyOrderDetailListByOwner(activityId, userId, 3);
            randomTeams = activityRepository.queryInProgressUserGroupBuyOrderDetailListByRandom(activityId, userId, 10);
            teamStatistic = activityRepository.queryTeamStatisticByActivityId(activityId);
        }
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0d;
        long dbDelta = marketConfigReadMetrics.dbLoadCount() - dbBefore;
        if (dbDelta == 0L) {
            marketConfigReadMetrics.recordCacheHit();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elapsedMs", elapsedMs);
        body.put("dbLoads", dbDelta);
        body.put("cacheHit", dbDelta == 0L);
        body.put("hasMapping", mapping != null);
        body.put("hasSku", sku != null);
        body.put("hasActivity", activity != null);
        body.put("activityId", activityId);
        body.put("ownerTeamCount", ownerTeams == null ? 0 : ownerTeams.size());
        body.put("randomTeamCount", randomTeams == null ? 0 : randomTeams.size());
        body.put("hasTeamStatistic", teamStatistic != null);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(body)
                .build();
    }

    private boolean authorized(String token) {
        return internalToken != null && !internalToken.isBlank() && internalToken.equals(token);
    }

    private <T> Response<T> unauthorized() {
        return Response.<T>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("invalid internal token")
                .build();
    }
}
