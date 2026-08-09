package com.aigroup.bff.controller;

import com.aigroup.bff.client.GroupFeignClient;
import com.aigroup.bff.client.MemberFeignClient;
import com.aigroup.bff.client.PayFeignClient;
import com.aigroup.bff.support.GroupMarketQueryCoordinator;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bff")
@RequiredArgsConstructor
public class BffController {

    /** Aligns with {@code MarketTypeVO.GROUP_BUY_MARKET}. */
    private static final int GROUP_BUY_MARKET_TYPE = 1;

    private final MemberFeignClient memberFeignClient;
    private final GroupFeignClient groupFeignClient;
    private final PayFeignClient payFeignClient;
    private final GroupMarketQueryCoordinator groupMarketQueryCoordinator;

    @Value("${ai-group.group.default-source:s01}")
    private String groupSource;

    @Value("${ai-group.group.default-channel:c01}")
    private String groupChannel;

    @Value("${ai-group.group.default-goods-id:9890002}")
    private String defaultGoodsId;

    @GetMapping("/pricing")
    public Result<Map<String, Object>> pricing() {
        requireUserId();
        Map<String, Object> data = new HashMap<>();
        DegradeContext degrade = new DegradeContext();
        List<Map<String, Object>> skus = listSkusSafe(degrade);
        // 按 SKU 各自的拼团商品查询营销配置，不同额度包不能共用一个默认活动。
        Map<String, Map<String, Object>> marketByGoods = queryGroupMarketsForSkus(skus, degrade);
        enrichSkusWithGroupBuy(skus, marketByGoods);
        data.put("skus", skus);
        data.put("groupBuy", buildAggregatedGroupBuy(skus, marketByGoods));
        data.put("meta", degrade.meta());
        return Result.success(data);
    }

    @GetMapping("/group-buy/{activityId}")
    public Result<Map<String, Object>> groupBuy(@PathVariable Long activityId) {
        requireUserId();
        Map<String, Object> data = new HashMap<>();
        DegradeContext degrade = new DegradeContext();
        List<Map<String, Object>> skus = listSkusSafe(degrade);
        data.put("activityId", activityId);
        // 按活动ID反查该 SKU 对应的拼团商品，返回该活动自己的队伍与价格
        String goodsId = resolveGoodsIdByActivity(skus, activityId);
        data.put("groupBuy", queryGroupMarketSafe(goodsId, degrade));
        Map<String, Map<String, Object>> marketByGoods = queryGroupMarketsForSkus(skus, degrade);
        enrichSkusWithGroupBuy(skus, marketByGoods);
        data.put("skus", skus);
        data.put("meta", degrade.meta());
        return Result.success(data);
    }

    @GetMapping("/account/summary")
    public Result<Map<String, Object>> accountSummary() {
        requireUserId();
        DegradeContext degrade = new DegradeContext();
        Result<Map<String, Object>> memberResult = memberFeignClient.summary();
        if (memberResult == null || memberResult.getCode() == null || memberResult.getCode() != 200) {
            String message = memberResult != null && memberResult.getMessage() != null
                    ? memberResult.getMessage()
                    : "member summary unavailable";
            throw new BusinessException(message);
        }
        Map<String, Object> summary = memberResult.getData();
        if (summary == null) {
            summary = new HashMap<>();
        }
        summary.put("quotaLedger", listQuotaLedgerSafe(degrade));
        summary.put("pendingGroupOrders", listPendingGroupOrdersSafe(degrade));
        summary.put("meta", degrade.meta());
        return Result.success(summary);
    }

    @GetMapping("/orders")
    public Result<Map<String, Object>> orders() {
        requireUserId();
        DegradeContext degrade = new DegradeContext();
        Map<String, Object> data = new HashMap<>();
        data.put("items", listUserOrdersSafe(degrade));
        data.put("meta", degrade.meta());
        return Result.success(data);
    }

    private Map<String, Object> queryGroupMarketSafe(String goodsId, DegradeContext degrade) {
        try {
            Long userId = requireUserId();
            return normalizeGroupMarket(groupMarketQueryCoordinator.query(userId, goodsId,
                    () -> groupFeignClient.queryGroupBuyMarketConfig(buildGroupMarketRequest(userId, goodsId))),
                    String.valueOf(userId));
        } catch (Exception ex) {
            degrade.add("group", "GROUP_MARKET_UNAVAILABLE", ex.getMessage());
            return Map.of("unavailable", true);
        }
    }

    private Map<String, Object> buildGroupMarketRequest(Long userId, String goodsId) {
        Map<String, Object> request = new HashMap<>();
        request.put("userId", String.valueOf(userId));
        request.put("source", groupSource);
        request.put("channel", groupChannel);
        request.put("goodsId", goodsId == null || goodsId.isBlank() ? defaultGoodsId : goodsId);
        return request;
    }

    /**
     * 汇总各 SKU 拼团商品的营销配置：goodsId -> 该商品的 groupBuy（activityId/goods/teamList）。
     * 无任何 SKU 配置拼团映射时退回默认商品，保持旧行为。
     */
    private Map<String, Map<String, Object>> queryGroupMarketsForSkus(List<Map<String, Object>> skus, DegradeContext degrade) {
        Map<String, Map<String, Object>> marketByGoods = new HashMap<>();
        for (Map<String, Object> sku : skus) {
            String goodsId = stringValue(sku.get("groupGoodsId"));
            if (goodsId == null || goodsId.isBlank() || marketByGoods.containsKey(goodsId)) {
                continue;
            }
            marketByGoods.put(goodsId, queryGroupMarketSafe(goodsId, degrade));
        }
        if (marketByGoods.isEmpty()) {
            marketByGoods.put(defaultGoodsId, queryGroupMarketSafe(defaultGoodsId, degrade));
        }
        return marketByGoods;
    }

    /**
     * 给每个 SKU 附加其拼团价格信息（groupPayPrice/groupDeductionPrice/groupOriginalPrice/groupActivityId）。
     */
    private void enrichSkusWithGroupBuy(List<Map<String, Object>> skus, Map<String, Map<String, Object>> marketByGoods) {
        for (Map<String, Object> sku : skus) {
            String goodsId = stringValue(sku.get("groupGoodsId"));
            if (goodsId == null || goodsId.isBlank()) {
                continue;
            }
            Map<String, Object> market = marketByGoods.get(goodsId);
            if (market == null || Boolean.TRUE.equals(market.get("unavailable"))) {
                continue;
            }
            Object goods = market.get("goods");
            if (goods instanceof Map<?, ?> goodsMap) {
                sku.put("groupPayPrice", goodsMap.get("payPrice"));
                sku.put("groupDeductionPrice", goodsMap.get("deductionPrice"));
                sku.put("groupOriginalPrice", goodsMap.get("originalPrice"));
            }
            Object activityId = market.get("activityId");
            if (activityId != null) {
                sku.put("groupActivityId", activityId);
            }
            Object targetCount = market.get("targetCount");
            if (targetCount != null) {
                sku.put("groupTeamSize", targetCount);
            }
            // Current quota packages use one fixed amount per SKU. Do not
            // expose the legacy activity-tier payload to the browser; the
            // activity still returns team progress and its configured cash
            // promotion through groupPayPrice/groupDeductionPrice.
        }
    }

    /**
     * 聚合大厅视图：合并所有拼团商品的进行中队伍（Team 自带 activityId 供前端归属 SKU），
     * 同时保留当前用户自己的队伍到 myTeamList；顶层 activityId/goods 取第一个可用商品，
     * 保持前端旧字段兼容。
     */
    private Map<String, Object> buildAggregatedGroupBuy(List<Map<String, Object>> skus, Map<String, Map<String, Object>> marketByGoods) {
        Map<String, Object> aggregated = null;
        List<Object> mergedTeams = new ArrayList<>();
        List<Object> mergedMyTeams = new ArrayList<>();
        // 按 SKU 顺序聚合，保证顶层默认取第一个额度包的活动。
        List<String> orderedGoods = new ArrayList<>();
        for (Map<String, Object> sku : skus) {
            String goodsId = stringValue(sku.get("groupGoodsId"));
            if (goodsId != null && !goodsId.isBlank() && !orderedGoods.contains(goodsId)) {
                orderedGoods.add(goodsId);
            }
        }
        if (orderedGoods.isEmpty()) {
            orderedGoods.addAll(marketByGoods.keySet());
        }
        for (String goodsId : orderedGoods) {
            Map<String, Object> market = marketByGoods.get(goodsId);
            if (market == null || Boolean.TRUE.equals(market.get("unavailable"))) {
                continue;
            }
            if (aggregated == null) {
                aggregated = new HashMap<>(market);
            }
            Object teams = market.get("teamList");
            if (teams instanceof List<?> teamList) {
                mergedTeams.addAll(teamList);
            }
            Object myTeams = market.get("myTeamList");
            if (myTeams instanceof List<?> myTeamList) {
                mergedMyTeams.addAll(myTeamList);
            }
        }
        if (aggregated == null) {
            return Map.of("unavailable", true);
        }
        aggregated.put("teamList", mergedTeams);
        aggregated.put("myTeamList", mergedMyTeams);
        return aggregated;
    }

    /**
     * 按拼团活动ID反查商品ID（用于 /group-buy/{activityId} 详情页取该活动自己的队伍）。
     */
    private String resolveGoodsIdByActivity(List<Map<String, Object>> skus, Long activityId) {
        if (activityId == null) {
            return defaultGoodsId;
        }
        for (Map<String, Object> sku : skus) {
            String goodsId = stringValue(sku.get("groupGoodsId"));
            String skuActivity = stringValue(sku.get("groupActivityId"));
            if (goodsId != null && !goodsId.isBlank() && String.valueOf(activityId).equals(skuActivity)) {
                return goodsId;
            }
        }
        return defaultGoodsId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeGroupMarket(Map<String, Object> response, String currentUserId) {
        if (response == null) {
            return Map.of("unavailable", true);
        }
        Object code = response.get("code");
        if (code != null && !"0000".equals(String.valueOf(code))) {
            return Map.of("unavailable", true);
        }
        Object payload = response.get("data");
        if (!(payload instanceof Map<?, ?> raw)) {
            if (response.containsKey("activityId") || response.containsKey("goods")) {
                Map<String, Object> normalized = new HashMap<>((Map<String, Object>) response);
                return splitCurrentUserTeams(normalized, currentUserId);
            }
            return Map.of("unavailable", true);
        }
        Map<String, Object> normalized = new HashMap<>();
        raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return splitCurrentUserTeams(normalized, currentUserId);
    }

    /**
     * The market service intentionally returns the current user's open teams first so that
     * order pages can render them. The public hall has a different meaning: its list is only
     * the teams that this user can join. Keep both views in the response so the UI can show
     * "我的进行中拼团" separately without allowing a user to join their own team.
     */
    private Map<String, Object> splitCurrentUserTeams(Map<String, Object> market, String currentUserId) {
        Object teams = market.get("teamList");
        if (!(teams instanceof List<?> teamList)) {
            return market;
        }
        // The group service returns the current user's memberships in
        // `myTeamList`, while `teamList` is the public list and may still
        // contain the same team (usually under its owner's userId).  Keep
        // the membership list authoritative and remove those teams from the
        // joinable list; otherwise a user who has joined a team sees it in
        // both sections and can attempt to join the same team again.
        List<Object> currentUserTeams = new ArrayList<>();
        java.util.Set<String> currentTeamIds = new java.util.HashSet<>();
        Object existingMine = market.get("myTeamList");
        if (existingMine instanceof List<?> mineList) {
            for (Object item : mineList) {
                if (!(item instanceof Map<?, ?> team)) {
                    continue;
                }
                String teamId = stringValue(team.get("teamId"));
                if (teamId != null && !teamId.isBlank() && currentTeamIds.add(teamId)) {
                    currentUserTeams.add(item);
                }
            }
        }
        List<Object> visibleTeams = new ArrayList<>();
        for (Object item : teamList) {
            if (!(item instanceof Map<?, ?> team)) {
                visibleTeams.add(item);
                continue;
            }
            String teamId = stringValue(team.get("teamId"));
            Object ownerId = team.get("userId");
            if ((teamId != null && currentTeamIds.contains(teamId))
                    || (ownerId != null && currentUserId != null && currentUserId.equals(String.valueOf(ownerId)))) {
                if (teamId == null || currentTeamIds.add(teamId)) {
                    currentUserTeams.add(item);
                }
            } else {
                visibleTeams.add(item);
            }
        }
        market.put("myTeamList", currentUserTeams);
        market.put("teamList", visibleTeams);
        return market;
    }

    private List<Map<String, Object>> listUserOrders() {
        Long userId = requireUserId();
        Map<String, Object> request = new HashMap<>();
        request.put("userId", String.valueOf(userId));
        request.put("lastId", null);
        request.put("pageSize", 20);
        Map<String, Object> response = payFeignClient.queryUserOrderList(request);
        return extractOrderList(response);
    }

    private List<Map<String, Object>> listUserOrdersSafe(DegradeContext degrade) {
        try {
            return listUserOrders();
        } catch (Exception ex) {
            degrade.add("pay", "ORDER_LIST_UNAVAILABLE", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> listPendingGroupOrdersSafe(DegradeContext degrade) {
        try {
            return listPendingGroupOrders();
        } catch (Exception ex) {
            degrade.add("bff", "PENDING_ORDERS_UNAVAILABLE", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> listQuotaLedgerSafe(DegradeContext degrade) {
        try {
            Result<List<Map<String, Object>>> result = memberFeignClient.quotaLedger();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                degrade.add("member", "QUOTA_LEDGER_UNAVAILABLE",
                        result == null ? null : result.getMessage());
                return List.of();
            }
            return result.getData() == null ? List.of() : result.getData();
        } catch (Exception ex) {
            degrade.add("member", "QUOTA_LEDGER_UNAVAILABLE", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> listSkusSafe(DegradeContext degrade) {
        try {
            Result<List<Map<String, Object>>> result = memberFeignClient.listSkus();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                degrade.add("member", "SKU_LIST_UNAVAILABLE", result == null ? null : result.getMessage());
                return List.of();
            }
            List<Map<String, Object>> data = result.getData();
            return data == null ? List.of() : data;
        } catch (Exception ex) {
            degrade.add("member", "SKU_LIST_UNAVAILABLE", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> listPendingGroupOrders() {
        List<Map<String, Object>> orders = listUserOrders();
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            String status = stringValue(order.get("displayStatus"));
            if ("PAID_WAIT_GROUP".equals(status)) {
                Map<String, Object> item = new HashMap<>();
                item.put("orderId", order.get("orderId"));
                item.put("status", status);
                item.put("productName", order.get("productName"));
                item.put("paidAt", order.get("paidAt"));
                pending.add(item);
            }
        }
        return pending;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractOrderList(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object orderList = dataMap.get("orderList");
        if (!(orderList instanceof List<?> rawList)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> orderMap) {
                Map<String, Object> mapped = new HashMap<>();
                String orderId = stringValue(orderMap.get("orderId"));
                String rawStatus = stringValue(orderMap.get("status"));
                Object marketType = orderMap.get("marketType");
                mapped.put("orderId", orderId);
                mapped.put("status", rawStatus);
                mapped.put("displayStatus", resolveDisplayStatus(rawStatus, marketType, orderId));
                mapped.put("productName", orderMap.get("productName"));
                // 展示金额 = 商品价 - 营销扣减（真实应付价）。沙箱小额模式下 payAmount 是 0.01 实收，
                // 不能作为展示价；无 totalAmount 的历史单退回 payAmount。
                mapped.put("amount", resolveDisplayAmount(
                        orderMap.get("totalAmount"), orderMap.get("marketDeductionAmount"), orderMap.get("payAmount")));
                mapped.put("paidAt", orderMap.get("payTime"));
                mapped.put("marketType", marketType);
                mapped.put("groupStatus", isGroupBuyMarket(marketType) ? mapGroupStatus(rawStatus) : null);
                // 透传收银台表单：pay 服务仅对 PAY_WAIT 订单回传 payUrl，
                // 前端订单中心据此渲染「去支付」按钮恢复支付（此前被丢弃导致待支付订单无法继续付款）。
                mapped.put("payUrl", orderMap.get("payUrl"));
                result.add(mapped);
            }
        }
        return result;
    }

    private String mapGroupStatus(String payStatus) {
        String normalized = normalizePayStatus(payStatus);
        return switch (normalized) {
            case "DEAL_DONE", "MARKET" -> "formed";
            case "PAY_SUCCESS" -> "waiting";
            default -> normalized.toLowerCase();
        };
    }

    private String mapDisplayStatus(String payStatus, Object marketType) {
        String normalized = normalizePayStatus(payStatus);
        return switch (normalized) {
            case "PAY_WAIT", "CREATE" -> "PAY_WAIT";
            case "PAY_SUCCESS" -> isGroupBuyMarket(marketType) ? "PAID_WAIT_GROUP" : "PAID";
            case "DEAL_DONE" -> isGroupBuyMarket(marketType) ? "GROUP_FORMED" : "PAID";
            case "MARKET" -> "GROUP_FORMED";
            case "WAIT_REFUND" -> "WAIT_REFUND";
            case "CLOSE" -> "CLOSED";
            default -> normalized;
        };
    }

    private String resolveDisplayStatus(String payStatus, Object marketType, String orderId) {
        String base = mapDisplayStatus(payStatus, marketType);
        String normalized = normalizePayStatus(payStatus);
        boolean benefitTerminal = "MARKET".equals(normalized) || "DEAL_DONE".equals(normalized);
        if (!benefitTerminal || orderId == null || orderId.isBlank()) {
            return base;
        }
        try {
            Result<Map<String, String>> result = memberFeignClient.benefitStatus(orderId);
            Map<String, String> benefit = result == null || result.getCode() == null || result.getCode() != 200
                    ? null : result.getData();
            String grantStatus = benefit == null
                    ? "PENDING" : benefit.getOrDefault("status", "PENDING").toUpperCase();
            return switch (grantStatus) {
                case "GRANTED" -> "BENEFIT_GRANTED";
                case "REVOKED" -> "CLOSED";
                default -> base;
            };
        } catch (Exception ex) {
            // Member 短暂不可用不能把 pay 的稳定终态降成 UNKNOWN；下一次刷新再收敛权益状态。
            return base;
        }
    }

    private String normalizePayStatus(String payStatus) {
        if (payStatus == null) {
            return "";
        }
        return payStatus.trim().toUpperCase();
    }

    /**
     * 订单展示金额：totalAmount(商品价) - marketDeductionAmount(拼团扣减)；缺项时退回实收 payAmount。
     */
    private Object resolveDisplayAmount(Object totalAmount, Object deductionAmount, Object payAmount) {
        java.math.BigDecimal total = toBigDecimal(totalAmount);
        if (total == null) {
            return payAmount;
        }
        java.math.BigDecimal deduction = toBigDecimal(deductionAmount);
        java.math.BigDecimal display = deduction == null ? total : total.subtract(deduction);
        if (display.compareTo(java.math.BigDecimal.ZERO) < 0) {
            return payAmount;
        }
        return display;
    }

    private java.math.BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long requireUserId() {
        return RequestUserContext.requireUserId();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean isGroupBuyMarket(Object marketType) {
        if (marketType instanceof Number number) {
            return number.intValue() == GROUP_BUY_MARKET_TYPE;
        }
        String text = stringValue(marketType);
        return String.valueOf(GROUP_BUY_MARKET_TYPE).equals(text) || "group_buy_market".equals(text);
    }

    private static final class DegradeContext {
        private final List<Map<String, Object>> errors = new ArrayList<>();

        void add(String service, String code, String message) {
            Map<String, Object> item = new HashMap<>();
            item.put("service", service);
            item.put("code", code);
            if (message != null && !message.isBlank()) {
                item.put("message", message);
            }
            errors.add(item);
        }

        Map<String, Object> meta() {
            return Map.of(
                    "degraded", !errors.isEmpty(),
                    "errors", List.copyOf(errors)
            );
        }
    }
}
