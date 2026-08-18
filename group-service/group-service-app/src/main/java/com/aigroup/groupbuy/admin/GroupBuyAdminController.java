package com.aigroup.groupbuy.admin;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyActivityDao;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyDiscountDao;
import com.aigroup.groupbuy.infrastructure.dao.ISCSkuActivityDao;
import com.aigroup.groupbuy.infrastructure.dao.ISkuDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivity;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyDiscount;
import com.aigroup.groupbuy.infrastructure.dao.po.SCSkuActivity;
import com.aigroup.groupbuy.infrastructure.dao.po.Sku;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼团运营端接口：活动/折扣/商品的查看与调整。
 *
 * <p>鉴权：仅接受经网关转发（X-Gateway-Request + X-Internal-Token）且 JWT role 为 ADMIN 的请求。
 * 角色以 {@link RequestUserContext} 为准，不信请求头 {@code X-Role}。活动/折扣在 Redis 有读缓存，更新后同步逐出。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/group/admin/")
public class GroupBuyAdminController {

    private static final String HEADER_GATEWAY_REQUEST = "X-Gateway-Request";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Resource
    private IGroupBuyActivityDao groupBuyActivityDao;
    @Resource
    private IGroupBuyDiscountDao groupBuyDiscountDao;
    @Resource
    private ISkuDao skuDao;
    @Resource
    private ISCSkuActivityDao scSkuActivityDao;
    @Resource
    private IRedisService redisService;

    /**
     * 活动列表（联查折扣与商品）：activity + discount(market_plan/expr) + goods(goods_id/name/original_price)。
     */
    @GetMapping("activities")
    public Response<List<Map<String, Object>>> listActivities(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return forbidden();
        }
        List<GroupBuyActivity> activities = groupBuyActivityDao.queryGroupBuyActivityList();
        List<GroupBuyDiscount> discounts = groupBuyDiscountDao.queryGroupBuyDiscountList();
        List<SCSkuActivity> mappings = scSkuActivityDao.querySCSkuActivityList();
        List<Sku> goods = skuDao.querySkuList();

        Map<String, GroupBuyDiscount> discountById = new HashMap<>();
        for (GroupBuyDiscount discount : discounts) {
            discountById.put(discount.getDiscountId(), discount);
        }
        Map<Long, SCSkuActivity> mappingByActivity = new HashMap<>();
        for (SCSkuActivity mapping : mappings) {
            mappingByActivity.put(mapping.getActivityId(), mapping);
        }
        Map<String, Sku> goodsById = new HashMap<>();
        for (Sku sku : goods) {
            goodsById.put(sku.getGoodsId(), sku);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupBuyActivity activity : activities) {
            Map<String, Object> row = new HashMap<>();
            row.put("activityId", activity.getActivityId());
            row.put("activityName", activity.getActivityName());
            row.put("discountId", activity.getDiscountId());
            row.put("takeLimitCount", activity.getTakeLimitCount());
            row.put("target", activity.getTarget());
            row.put("validTime", activity.getValidTime());
            row.put("status", activity.getStatus());
            row.put("startTime", activity.getStartTime());
            row.put("endTime", activity.getEndTime());
            GroupBuyDiscount discount = discountById.get(activity.getDiscountId());
            if (discount != null) {
                row.put("discountName", discount.getDiscountName());
                row.put("marketPlan", discount.getMarketPlan());
                row.put("marketExpr", discount.getMarketExpr());
            }
            SCSkuActivity mapping = mappingByActivity.get(activity.getActivityId());
            if (mapping != null) {
                row.put("goodsId", mapping.getGoodsId());
                Sku sku = goodsById.get(mapping.getGoodsId());
                if (sku != null) {
                    row.put("goodsName", sku.getGoodsName());
                    row.put("originalPrice", sku.getOriginalPrice());
                    row.put("groupPayPrice", resolveGroupPayPrice(sku.getOriginalPrice(), discount));
                }
            }
            result.add(row);
        }
        return Response.<List<Map<String, Object>>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(result)
                .build();
    }

    /** 创建活动、折扣、商品和渠道映射，作为一个本地事务提交。 */
    @PostMapping("activities")
    @Transactional(rollbackFor = Exception.class)
    public Response<Boolean> createActivity(@RequestBody Map<String, Object> body,
                                            HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Response.<Boolean>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("admin role required").build();
        }
        try {
            Long activityId = longValue(body.get("activityId"));
            String activityName = requiredString(body, "activityName");
            String goodsId = requiredString(body, "goodsId");
            String goodsName = requiredString(body, "goodsName");
            String discountId = requiredString(body, "discountId");
            BigDecimal originalPrice = decimalValue(body.get("originalPrice"));
            String marketExpr = requiredString(body, "marketExpr");
            String marketPlan = normalizeMarketPlan(body.get("marketPlan"));
            if (activityId == null || originalPrice == null) {
                throw new IllegalArgumentException("activityId and originalPrice are required");
            }

            groupBuyDiscountDao.insertGroupBuyDiscount(GroupBuyDiscount.builder()
                    .discountId(discountId).discountName(activityName + "优惠")
                    .discountDesc(activityName + "运营配置").discountType(0)
                    .marketPlan(marketPlan).marketExpr(marketExpr).build());
            skuDao.insertSku(Sku.builder().source("s01").channel("c01")
                    .goodsId(goodsId).goodsName(goodsName).originalPrice(originalPrice).build());
            groupBuyActivityDao.insertGroupBuyActivity(GroupBuyActivity.builder()
                    .activityId(activityId).activityName(activityName).discountId(discountId)
                    .groupType(0)
                    .takeLimitCount(defaultInt(body.get("takeLimitCount"), 10))
                    .target(defaultTarget(body.get("target")))
                    .validTime(defaultInt(body.get("validTime"), 1440))
                    .status(defaultInt(body.get("status"), 1)).build());
            scSkuActivityDao.insertSCSkuActivity(SCSkuActivity.builder()
                    .source("s01").channel("c01").activityId(activityId).goodsId(goodsId).build());
            return Response.<Boolean>builder().code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo()).data(Boolean.TRUE).build();
        } catch (Exception e) {
            log.error("admin create group-buy activity failed", e);
            throw new IllegalStateException("create group-buy activity failed", e);
        }
    }

    /**
     * 更新活动运营参数与拼团价：
     * body 可含 activityName/takeLimitCount/target/validTime/status（活动表）、
     * marketExpr（折扣表，ZJ 直减金额）、goodsName/originalPrice（商品表）。
     * 更新后逐出活动与折扣的 Redis 读缓存，立即生效。
     */
    @PutMapping("activities/{activityId}")
    public Response<Boolean> updateActivity(@PathVariable Long activityId,
                                            @RequestBody Map<String, Object> body,
                                            HttpServletRequest request) {
        if (!isAdmin(request)) {
            return Response.<Boolean>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("admin role required")
                    .build();
        }
        try {
            GroupBuyActivity activity = groupBuyActivityDao.queryGroupBuyActivityByActivityId(activityId);
            if (activity == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("activity not found: " + activityId)
                        .build();
            }

            GroupBuyActivity activityUpdate = GroupBuyActivity.builder()
                    .activityId(activityId)
                    .activityName(stringValue(body.get("activityName")))
                    .takeLimitCount(intValue(body.get("takeLimitCount")))
                    .target(targetValue(body.get("target")))
                    .validTime(intValue(body.get("validTime")))
                    .status(intValue(body.get("status")))
                    .build();
            groupBuyActivityDao.updateGroupBuyActivityConfig(activityUpdate);

            String marketExpr = stringValue(body.get("marketExpr"));
            String marketPlan = body.containsKey("marketPlan") ? normalizeMarketPlan(body.get("marketPlan")) : null;
            if (StringUtils.isNotBlank(marketExpr) || StringUtils.isNotBlank(marketPlan)) {
                GroupBuyDiscount discountUpdate = GroupBuyDiscount.builder()
                        .discountId(activity.getDiscountId())
                        .marketPlan(marketPlan)
                        .marketExpr(marketExpr)
                        .build();
                groupBuyDiscountDao.updateGroupBuyDiscountExpr(discountUpdate);
            }

            String goodsName = stringValue(body.get("goodsName"));
            BigDecimal originalPrice = decimalValue(body.get("originalPrice"));
            if (StringUtils.isNotBlank(goodsName) || originalPrice != null) {
                SCSkuActivity mapping = findMapping(activityId);
                if (mapping != null) {
                    skuDao.updateSkuGoods(Sku.builder()
                            .goodsId(mapping.getGoodsId())
                            .goodsName(goodsName)
                            .originalPrice(originalPrice)
                            .build());
                }
            }

            // 读路径带 Redis 缓存，更新后必须逐出，否则前台仍读旧价
            redisService.remove(GroupBuyActivity.cacheRedisKey(activityId));
            redisService.remove(GroupBuyDiscount.cacheRedisKey(activity.getDiscountId()));

            log.info("admin updated group-buy activity {} body={}", activityId, body.keySet());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(Boolean.TRUE)
                    .build();
        } catch (Exception e) {
            log.error("admin update group-buy activity failed activityId={}", activityId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private SCSkuActivity findMapping(Long activityId) {
        for (SCSkuActivity mapping : scSkuActivityDao.querySCSkuActivityList()) {
            if (activityId.equals(mapping.getActivityId())) {
                return mapping;
            }
        }
        return null;
    }

    private Object resolveGroupPayPrice(BigDecimal originalPrice, GroupBuyDiscount discount) {
        if (originalPrice == null || discount == null) {
            return null;
        }
        try {
            String plan = discount.getMarketPlan();
            String expr = discount.getMarketExpr();
            BigDecimal price;
            if ("ZJ".equals(plan)) {
                price = originalPrice.subtract(new BigDecimal(expr));
            } else if ("MJ".equals(plan)) {
                String[] parts = expr.split(",");
                if (parts.length != 2) return null;
                BigDecimal threshold = new BigDecimal(parts[0].trim());
                BigDecimal deduction = new BigDecimal(parts[1].trim());
                price = originalPrice.compareTo(threshold) >= 0 ? originalPrice.subtract(deduction) : originalPrice;
            } else if ("ZK".equals(plan)) {
                price = originalPrice.multiply(new BigDecimal(expr));
            } else if ("N".equals(plan)) {
                price = new BigDecimal(expr);
            } else {
                return null;
            }
            price = price.setScale(2, java.math.RoundingMode.HALF_UP);
            return price.compareTo(BigDecimal.ZERO) <= 0 ? new BigDecimal("0.01") : price;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeMarketPlan(Object value) {
        String plan = StringUtils.upperCase(StringUtils.trimToEmpty(stringValue(value)));
        if (!List.of("ZJ", "MJ", "ZK", "N").contains(plan)) {
            throw new IllegalArgumentException("marketPlan must be one of ZJ, MJ, ZK, N");
        }
        return plan;
    }

    /**
     * 管理端信任模型：必须经网关（X-Gateway-Request=true 且内部令牌一致），
     * 且 JWT 绑定的 role 为 ADMIN。
     */
    public boolean isAdmin(HttpServletRequest request) {
        if (!"true".equalsIgnoreCase(request.getHeader(HEADER_GATEWAY_REQUEST))) {
            return false;
        }
        if (StringUtils.isBlank(internalToken) || !internalToken.equals(request.getHeader(HEADER_INTERNAL_TOKEN))) {
            return false;
        }
        return "ADMIN".equalsIgnoreCase(RequestUserContext.getRole());
    }

    private Response<List<Map<String, Object>>> forbidden() {
        return Response.<List<Map<String, Object>>>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("admin role required")
                .build();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return Long.valueOf(String.valueOf(value));
    }

    private Integer defaultInt(Object value, int fallback) {
        Integer parsed = intValue(value);
        return parsed == null ? fallback : parsed;
    }

    private int defaultTarget(Object value) {
        Integer parsed = targetValue(value);
        return parsed == null ? 3 : parsed;
    }

    private Integer targetValue(Object value) {
        Integer parsed = intValue(value);
        if (parsed == null) {
            return null;
        }
        if (parsed < 2 || parsed > 100) {
            throw new IllegalArgumentException("target must be between 2 and 100");
        }
        return parsed;
    }

    private String requiredString(Map<String, Object> body, String key) {
        String value = stringValue(body.get(key));
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
}
