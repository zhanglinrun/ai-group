package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.domain.activity.adapter.repository.IActivityRepository;
import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.*;
import com.aigroup.groupbuy.infrastructure.dao.*;
import com.aigroup.groupbuy.infrastructure.dao.po.*;
import com.aigroup.groupbuy.infrastructure.cache.HallDetailCacheKeys;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBitSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @description 活动仓储
 * @create 2024-12-21 10:10
 */
@Repository
public class ActivityRepository extends AbstractRepository implements IActivityRepository {

    @Resource
    private IGroupBuyActivityDao groupBuyActivityDao;
    @Resource
    private IGroupBuyDiscountDao groupBuyDiscountDao;
    @Resource
    private ISkuDao skuDao;
    @Resource
    private ISCSkuActivityDao skuActivityDao;
    @Resource
    private IGroupBuyOrderDao groupBuyOrderDao;
    @Resource
    private IGroupBuyOrderListDao groupBuyOrderListDao;

    @Value("${group.hall-cache.ttl-ms:5000}")
    private long hallCacheTtlMs;
    @Value("${group.hall-cache.progress-pool-size:40}")
    private int hallProgressPoolSize;

    /** Sentinel userId so SQL "user_id !=" keeps every real team in the shared hall pool. */
    private static final String HALL_POOL_USER = "__hall_cache_pool__";

    @Override
    public GroupBuyActivityDiscountVO queryGroupBuyActivityDiscountVO(Long activityId) {
        return loadGroupBuyActivityDiscountVO(activityId);
    }

    private GroupBuyActivityDiscountVO loadGroupBuyActivityDiscountVO(Long activityId) {
        // Redis Cache Aside；运营更新走删缓存 + 延迟双删。
        GroupBuyActivity groupBuyActivityRes = getFromCacheOrDb(GroupBuyActivity.cacheRedisKey(activityId),
                () -> groupBuyActivityDao.queryValidGroupBuyActivityId(activityId));
        if (null == groupBuyActivityRes) return null;

        String discountId = groupBuyActivityRes.getDiscountId();

        GroupBuyDiscount groupBuyDiscountRes = getFromCacheOrDb(GroupBuyDiscount.cacheRedisKey(discountId),
                () -> groupBuyDiscountDao.queryGroupBuyActivityDiscountByDiscountId(discountId));
        if (null == groupBuyDiscountRes) return null;

        GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount = GroupBuyActivityDiscountVO.GroupBuyDiscount.builder()
                .discountName(groupBuyDiscountRes.getDiscountName())
                .discountDesc(groupBuyDiscountRes.getDiscountDesc())
                .discountType(DiscountTypeEnum.get(groupBuyDiscountRes.getDiscountType()))
                .marketPlan(groupBuyDiscountRes.getMarketPlan())
                .marketExpr(groupBuyDiscountRes.getMarketExpr())
                .tagId(groupBuyDiscountRes.getTagId())
                .build();

        return GroupBuyActivityDiscountVO.builder()
                .activityId(groupBuyActivityRes.getActivityId())
                .activityName(groupBuyActivityRes.getActivityName())
                .groupBuyDiscount(groupBuyDiscount)
                .groupType(groupBuyActivityRes.getGroupType())
                .takeLimitCount(groupBuyActivityRes.getTakeLimitCount())
                .target(groupBuyActivityRes.getTarget())
                .validTime(groupBuyActivityRes.getValidTime())
                .status(groupBuyActivityRes.getStatus())
                .startTime(groupBuyActivityRes.getStartTime())
                .endTime(groupBuyActivityRes.getEndTime())
                .tagId(groupBuyActivityRes.getTagId())
                .tagScope(groupBuyActivityRes.getTagScope())
                .build();
    }

    @Override
    public SkuVO querySkuByGoodsId(String goodsId) {
        return loadSkuVO(goodsId);
    }

    private SkuVO loadSkuVO(String goodsId) {
        Sku sku = getFromCacheOrDb(Sku.cacheRedisKey(goodsId), () -> skuDao.querySkuByGoodsId(goodsId));
        if (null == sku) return null;
        return SkuVO.builder()
                .goodsId(sku.getGoodsId())
                .goodsName(sku.getGoodsName())
                .originalPrice(sku.getOriginalPrice())
                .build();
    }

    public static String activityDiscountCacheKey(Long activityId) {
        return "group_buy_market_activity_discount_" + activityId;
    }

    @Override
    public SCSkuActivityVO querySCSkuActivityBySCGoodsId(String source, String channel, String goodsId) {
        return loadSCSkuActivityVO(source, channel, goodsId);
    }

    private SCSkuActivityVO loadSCSkuActivityVO(String source, String channel, String goodsId) {
        SCSkuActivity scSkuActivityReq = new SCSkuActivity();
        scSkuActivityReq.setSource(source);
        scSkuActivityReq.setChannel(channel);
        scSkuActivityReq.setGoodsId(goodsId);

        SCSkuActivity scSkuActivity = getFromCacheOrDb(SCSkuActivity.cacheRedisKey(source, channel, goodsId),
                () -> skuActivityDao.querySCSkuActivityBySCGoodsId(scSkuActivityReq));
        if (null == scSkuActivity) return null;

        return SCSkuActivityVO.builder()
                .source(scSkuActivity.getSource())
                .chanel(scSkuActivity.getChannel())
                .activityId(scSkuActivity.getActivityId())
                .goodsId(scSkuActivity.getGoodsId())
                .build();
    }

    @Override
    public boolean isTagCrowdRange(String tagId, String userId) {
        RBitSet bitSet = redisService.getBitSet(tagId);
        if (!bitSet.isExists()) return true;
        // 判断用户是否存在人群中
        return bitSet.get(redisService.getIndexFromUserId(userId));
    }

    @Override
    public boolean downgradeSwitch() {
        return dccService.isDowngradeSwitch();
    }

    @Override
    public boolean cutRange(String userId) {
        return dccService.isCutRange(userId);
    }

    @Override
    public List<UserGroupBuyOrderDetailEntity> queryInProgressUserGroupBuyOrderDetailListByOwner(Long activityId, String userId, Integer ownerCount) {
        if (!dccService.isCacheOpenSwitch() || activityId == null || StringUtils.isBlank(userId)) {
            return loadOwnerTeams(activityId, userId, ownerCount);
        }
        String key = HallDetailCacheKeys.ownerTeams(activityId, userId);
        return getFromCacheOrDb(key, () -> loadOwnerTeams(activityId, userId, ownerCount), hallCacheTtlMs);
    }

    private List<UserGroupBuyOrderDetailEntity> loadOwnerTeams(Long activityId, String userId, Integer ownerCount) {
        // 1. 根据用户ID、活动ID，查询用户参与的拼团队伍
        GroupBuyOrderList groupBuyOrderListReq = new GroupBuyOrderList();
        groupBuyOrderListReq.setActivityId(activityId);
        groupBuyOrderListReq.setUserId(userId);
        groupBuyOrderListReq.setCount(ownerCount);
        List<GroupBuyOrderList> groupBuyOrderLists = groupBuyOrderListDao.queryInProgressUserGroupBuyOrderDetailListByUserId(groupBuyOrderListReq);
        if (null == groupBuyOrderLists || groupBuyOrderLists.isEmpty()) return null;

        // 2. 过滤队伍获取 TeamId
        Set<String> teamIds = groupBuyOrderLists.stream()
                .map(GroupBuyOrderList::getTeamId)
                .filter(teamId -> teamId != null && !teamId.isEmpty()) // 过滤非空和非空字符串
                .collect(Collectors.toSet());

        // 3. 查询队伍明细，组装Map结构
        // 自己的队伍允许 complete_count=0：创建订单后已经锁定库存，用户仍需在
        // 收银台完成支付；这类队伍必须能在“我的进行中拼团”中恢复出来。
        List<GroupBuyOrder> groupBuyOrders = groupBuyOrderDao.queryGroupBuyProgressByTeamIdsForOwner(teamIds);
        if (null == groupBuyOrders || groupBuyOrders.isEmpty()) return null;

        Map<String, GroupBuyOrder> groupBuyOrderMap = groupBuyOrders.stream()
                .collect(Collectors.toMap(GroupBuyOrder::getTeamId, order -> order));

        // 4. 转换数据
        List<UserGroupBuyOrderDetailEntity> userGroupBuyOrderDetailEntities = new ArrayList<>();
        for (GroupBuyOrderList groupBuyOrderList : groupBuyOrderLists) {
            String teamId = groupBuyOrderList.getTeamId();
            GroupBuyOrder groupBuyOrder = groupBuyOrderMap.get(teamId);
            if (null == groupBuyOrder) continue;

            UserGroupBuyOrderDetailEntity userGroupBuyOrderDetailEntity = UserGroupBuyOrderDetailEntity.builder()
                    .userId(groupBuyOrderList.getUserId())
                    .teamId(groupBuyOrder.getTeamId())
                    .activityId(groupBuyOrder.getActivityId())
                    .targetCount(groupBuyOrder.getTargetCount())
                    .completeCount(groupBuyOrder.getCompleteCount())
                    .lockCount(groupBuyOrder.getLockCount())
                    .validStartTime(groupBuyOrder.getValidStartTime())
                    .validEndTime(groupBuyOrder.getValidEndTime())
                    .outTradeNo(groupBuyOrderList.getOutTradeNo())
                    .build();

            userGroupBuyOrderDetailEntities.add(userGroupBuyOrderDetailEntity);
        }

        return userGroupBuyOrderDetailEntities;
    }

    @Override
    public List<UserGroupBuyOrderDetailEntity> queryInProgressUserGroupBuyOrderDetailListByRandom(Long activityId, String userId, Integer randomCount) {
        if (randomCount == null || randomCount <= 0) {
            return null;
        }
        List<UserGroupBuyOrderDetailEntity> pool;
        if (dccService.isCacheOpenSwitch() && activityId != null) {
            String key = HallDetailCacheKeys.progressPool(activityId);
            int poolSize = Math.max(hallProgressPoolSize, randomCount * 2);
            pool = getFromCacheOrDb(key, () -> loadProgressPool(activityId, poolSize), hallCacheTtlMs);
        } else {
            pool = loadRandomTeams(activityId, userId, randomCount);
            return pool;
        }
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        List<UserGroupBuyOrderDetailEntity> filtered = new ArrayList<>();
        for (UserGroupBuyOrderDetailEntity item : pool) {
            if (item == null || StringUtils.isBlank(item.getUserId())) {
                continue;
            }
            if (item.getUserId().equals(userId)) {
                continue;
            }
            filtered.add(item);
        }
        if (filtered.isEmpty()) {
            return null;
        }
        if (filtered.size() > randomCount) {
            Collections.shuffle(filtered);
            return new ArrayList<>(filtered.subList(0, randomCount));
        }
        return filtered;
    }

    /** Shared activity pool (no per-user filter) used for hall random cards. */
    private List<UserGroupBuyOrderDetailEntity> loadProgressPool(Long activityId, int poolSize) {
        return loadRandomTeams(activityId, HALL_POOL_USER, poolSize);
    }

    private List<UserGroupBuyOrderDetailEntity> loadRandomTeams(Long activityId, String userId, Integer randomCount) {
        // 1. 根据用户ID、活动ID，查询用户参与的拼团队伍
        GroupBuyOrderList groupBuyOrderListReq = new GroupBuyOrderList();
        groupBuyOrderListReq.setActivityId(activityId);
        groupBuyOrderListReq.setUserId(userId);
        groupBuyOrderListReq.setCount(randomCount * 2); // 查询2倍的量，之后其中 randomCount 数量
        List<GroupBuyOrderList> groupBuyOrderLists = groupBuyOrderListDao.queryInProgressUserGroupBuyOrderDetailListByRandom(groupBuyOrderListReq);
        if (null == groupBuyOrderLists || groupBuyOrderLists.isEmpty()) return null;

        // 判断总量是否大于 randomCount
        if (groupBuyOrderLists.size() > randomCount) {
            // 随机打乱列表
            Collections.shuffle(groupBuyOrderLists);
            // 获取前 randomCount 个元素
            groupBuyOrderLists = groupBuyOrderLists.subList(0, randomCount);
        }

        // 2. 过滤队伍获取 TeamId
        Set<String> teamIds = groupBuyOrderLists.stream()
                .map(GroupBuyOrderList::getTeamId)
                .filter(teamId -> teamId != null && !teamId.isEmpty()) // 过滤非空和非空字符串
                .collect(Collectors.toSet());

        // 3. 查询队伍明细，组装Map结构
        List<GroupBuyOrder> groupBuyOrders = groupBuyOrderDao.queryGroupBuyProgressByTeamIds(teamIds);
        if (null == groupBuyOrders || groupBuyOrders.isEmpty()) return null;

        Map<String, GroupBuyOrder> groupBuyOrderMap = groupBuyOrders.stream()
                .collect(Collectors.toMap(GroupBuyOrder::getTeamId, order -> order));

        // 4. 转换数据
        List<UserGroupBuyOrderDetailEntity> userGroupBuyOrderDetailEntities = new ArrayList<>();
        for (GroupBuyOrderList groupBuyOrderList : groupBuyOrderLists) {
            String teamId = groupBuyOrderList.getTeamId();
            GroupBuyOrder groupBuyOrder = groupBuyOrderMap.get(teamId);
            if (null == groupBuyOrder) continue;

            UserGroupBuyOrderDetailEntity userGroupBuyOrderDetailEntity = UserGroupBuyOrderDetailEntity.builder()
                    .userId(groupBuyOrderList.getUserId())
                    .teamId(groupBuyOrder.getTeamId())
                    .activityId(groupBuyOrder.getActivityId())
                    .targetCount(groupBuyOrder.getTargetCount())
                    .completeCount(groupBuyOrder.getCompleteCount())
                    .lockCount(groupBuyOrder.getLockCount())
                    .validStartTime(groupBuyOrder.getValidStartTime())
                    .validEndTime(groupBuyOrder.getValidEndTime())
                    .build();

            userGroupBuyOrderDetailEntities.add(userGroupBuyOrderDetailEntity);
        }

        return userGroupBuyOrderDetailEntities;
    }

    @Override
    public TeamStatisticVO queryTeamStatisticByActivityId(Long activityId) {
        if (!dccService.isCacheOpenSwitch() || activityId == null) {
            return loadTeamStatistic(activityId);
        }
        return getFromCacheOrDb(HallDetailCacheKeys.teamStatistic(activityId),
                () -> loadTeamStatistic(activityId), hallCacheTtlMs);
    }

    private TeamStatisticVO loadTeamStatistic(Long activityId) {
        GroupBuyTeamStatistic statistic = groupBuyOrderDao.queryTeamStatisticByActivityId(activityId);
        if (statistic == null) {
            return new TeamStatisticVO(0, 0, 0);
        }
        return TeamStatisticVO.builder()
                .allTeamCount(toInt(statistic.getAllTeamCount()))
                .allTeamCompleteCount(toInt(statistic.getAllTeamCompleteCount()))
                .allTeamUserCount(toInt(statistic.getAllTeamUserCount()))
                .build();
    }

    private static int toInt(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

}
