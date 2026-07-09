package com.aigroup.bff.controller;

import com.aigroup.bff.client.GroupFeignClient;
import com.aigroup.bff.client.MemberFeignClient;
import com.aigroup.bff.client.PayFeignClient;
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

    @Value("${ai-group.group.default-source:s01}")
    private String groupSource;

    @Value("${ai-group.group.default-channel:c01}")
    private String groupChannel;

    @Value("${ai-group.group.default-goods-id:9890001}")
    private String defaultGoodsId;

    @GetMapping("/pricing")
    public Result<Map<String, Object>> pricing() {
        Map<String, Object> data = new HashMap<>();
        DegradeContext degrade = new DegradeContext();
        data.put("skus", listSkusSafe(degrade));
        data.put("groupBuy", queryGroupMarketSafe(degrade));
        data.put("meta", degrade.meta());
        return Result.success(data);
    }

    @GetMapping("/group-buy/{activityId}")
    public Result<Map<String, Object>> groupBuy(@PathVariable Long activityId) {
        Map<String, Object> data = new HashMap<>();
        DegradeContext degrade = new DegradeContext();
        data.put("activityId", activityId);
        data.put("groupBuy", queryGroupMarketSafe(degrade));
        data.put("skus", listSkusSafe(degrade));
        data.put("meta", degrade.meta());
        return Result.success(data);
    }

    @GetMapping("/account/summary")
    public Result<Map<String, Object>> accountSummary() {
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
        summary.put("pendingGroupOrders", listPendingGroupOrdersSafe(degrade));
        summary.put("meta", degrade.meta());
        return Result.success(summary);
    }

    @GetMapping("/orders")
    public Result<Map<String, Object>> orders() {
        DegradeContext degrade = new DegradeContext();
        Map<String, Object> data = new HashMap<>();
        data.put("items", listUserOrdersSafe(degrade));
        data.put("meta", degrade.meta());
        return Result.success(data);
    }

    private Map<String, Object> queryGroupMarketSafe(DegradeContext degrade) {
        try {
            return normalizeGroupMarket(groupFeignClient.queryGroupBuyMarketConfig(buildGroupMarketRequest()));
        } catch (Exception ex) {
            degrade.add("group", "GROUP_MARKET_UNAVAILABLE", ex.getMessage());
            return Map.of("unavailable", true);
        }
    }

    private Map<String, Object> queryGroupMarket() {
        return normalizeGroupMarket(groupFeignClient.queryGroupBuyMarketConfig(buildGroupMarketRequest()));
    }

    private Map<String, Object> buildGroupMarketRequest() {
        Long userId = requireUserId();
        Map<String, Object> request = new HashMap<>();
        request.put("userId", String.valueOf(userId));
        request.put("source", groupSource);
        request.put("channel", groupChannel);
        request.put("goodsId", defaultGoodsId);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeGroupMarket(Map<String, Object> response) {
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
                return new HashMap<>((Map<String, Object>) response);
            }
            return Map.of("unavailable", true);
        }
        Map<String, Object> normalized = new HashMap<>();
        raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
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
                mapped.put("orderId", orderId);
                mapped.put("status", rawStatus);
                mapped.put("displayStatus", resolveDisplayStatus(rawStatus, orderMap.get("marketType"), orderId));
                mapped.put("productName", orderMap.get("productName"));
                mapped.put("amount", orderMap.get("payAmount"));
                mapped.put("paidAt", orderMap.get("payTime"));
                mapped.put("marketType", orderMap.get("marketType"));
                mapped.put("groupStatus", mapGroupStatus(rawStatus));
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
            case "DEAL_DONE" -> "GROUP_FORMED";
            case "MARKET" -> "GROUP_FORMED";
            case "WAIT_REFUND" -> "WAIT_REFUND";
            case "CLOSE" -> "CLOSED";
            default -> normalized;
        };
    }

    private String resolveDisplayStatus(String payStatus, Object marketType, String orderId) {
        String base = mapDisplayStatus(payStatus, marketType);
        if (!"MARKET".equals(normalizePayStatus(payStatus)) || orderId == null || orderId.isBlank()) {
            return base;
        }
        try {
            Map<String, String> benefit = memberFeignClient.benefitStatus(orderId).getData();
            String grantStatus = benefit == null ? "PENDING" : benefit.getOrDefault("status", "PENDING");
            return switch (grantStatus) {
                case "GRANTED" -> "BENEFIT_GRANTED";
                case "REVOKED" -> "CLOSED";
                default -> "GROUP_FORMED";
            };
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private String normalizePayStatus(String payStatus) {
        if (payStatus == null) {
            return "";
        }
        return payStatus.trim().toUpperCase();
    }

    private Long requireUserId() {
        Long userId = RequestUserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        return userId;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
