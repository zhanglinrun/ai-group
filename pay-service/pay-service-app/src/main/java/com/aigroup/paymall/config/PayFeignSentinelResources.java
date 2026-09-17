package com.aigroup.paymall.config;

import java.util.List;

/**
 * Stable Sentinel resource names for Pay Feign calls.
 * <p>
 * SCA's default Feign resource is {@code METHOD:http://<target-url><path>}, which
 * changes when local profile sets a hardcoded host. These names are
 * {@code <feignClientName>#<methodName>} and stay the same across discovery and
 * direct URL.
 */
public final class PayFeignSentinelResources {

    public static final String GROUP_SERVICE = "group-service";
    public static final String MEMBER_SERVICE = "member-service";

    public static final String LOCK = resource(GROUP_SERVICE, "lockMarketPayOrder");
    public static final String QUERY_LOCK = resource(GROUP_SERVICE, "queryMarketPayOrder");
    public static final String SETTLEMENT = resource(GROUP_SERVICE, "settlementMarketPayOrder");
    public static final String REFUND = resource(GROUP_SERVICE, "refundMarketPayOrder");
    public static final String MEMBER_SKU = resource(MEMBER_SERVICE, "queryEnabledSkuByGoodsId");

    public static final List<String> ALL = List.of(LOCK, QUERY_LOCK, SETTLEMENT, REFUND, MEMBER_SKU);

    private PayFeignSentinelResources() {
    }

    public static String resource(String serviceId, String methodName) {
        return serviceId + "#" + methodName;
    }
}
