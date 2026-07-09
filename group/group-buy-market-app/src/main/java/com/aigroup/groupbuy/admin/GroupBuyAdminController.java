package com.aigroup.groupbuy.admin;

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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼团运营端接口：活动/折扣/商品的查看与调整。
 *
 * <p>鉴权：仅接受经网关转发（X-Gateway-Request + X-Internal-Token）且角色为 ADMIN 的请求，
 * 与 pay 侧 GatewayUserResolver 的信任模型一致。活动/折扣在 Redis 有读缓存，更新后同步逐出。</p>
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/group/admin/")
public class GroupBuyAdminController {

    /** 与 ai-group-common CommonConstant 对齐（group 模块不依赖 common，这里直接使用字面量） */
    private static final String HEADER_ROLE = "X-Role";
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
                    .target(intValue(body.get("target")))
                    .validTime(intValue(body.get("validTime")))
                    .status(intValue(body.get("status")))
                    .build();
            groupBuyActivityDao.updateGroupBuyActivityConfig(activityUpdate);

            String marketExpr = stringValue(body.get("marketExpr"));
            if (StringUtils.isNotBlank(marketExpr)) {
                GroupBuyDiscount discountUpdate = GroupBuyDiscount.builder()
                        .discountId(activity.getDiscountId())
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
        if (originalPrice == null || discount == null || !"ZJ".equals(discount.getMarketPlan())) {
            return null;
        }
        try {
            BigDecimal price = originalPrice.subtract(new BigDecimal(discount.getMarketExpr()));
            return price.compareTo(BigDecimal.ZERO) <= 0 ? new BigDecimal("0.01") : price;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 管理端信任模型：必须经网关（X-Gateway-Request=true 且内部令牌一致）且 JWT 角色为 ADMIN。
     */
    private boolean isAdmin(HttpServletRequest request) {
        if (!"true".equalsIgnoreCase(request.getHeader(HEADER_GATEWAY_REQUEST))) {
            return false;
        }
        if (StringUtils.isBlank(internalToken) || !internalToken.equals(request.getHeader(HEADER_INTERNAL_TOKEN))) {
            return false;
        }
        return "ADMIN".equalsIgnoreCase(request.getHeader(HEADER_ROLE));
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
}
