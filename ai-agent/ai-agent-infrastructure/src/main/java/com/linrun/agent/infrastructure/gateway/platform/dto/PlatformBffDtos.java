package com.linrun.agent.infrastructure.gateway.platform.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Feign wire DTOs for the existing /api/bff read endpoints. */
public final class PlatformBffDtos {

    private PlatformBffDtos() {
    }

    @Data
    public static class MetaDto {
        private Boolean degraded;
        private List<DegradationDto> errors;
    }

    @Data
    public static class DegradationDto {
        private String service;
        private String code;
        private String message;
    }

    @Data
    public static class AccountSummaryDto {
        private Long userId;
        private Long freeQuotaBalance;
        private Long paidQuotaBalance;
        private Long frozenBalance;
        private Long availableQuota;
        private List<QuotaLedgerEntryDto> quotaLedger;
        private List<PendingGroupOrderDto> pendingGroupOrders;
        private MetaDto meta;
    }

    @Data
    public static class QuotaLedgerEntryDto {
        private Long id;
        private String type;
        private Long amount;
        private String freezeId;
        private String abilityCode;
        private String remark;
        private String createdAt;
    }

    @Data
    public static class PendingGroupOrderDto {
        private String orderId;
        private String status;
        private String productName;
        private String paidAt;
    }

    @Data
    public static class PricingDto {
        private List<SkuDto> skus;
        private GroupBuyInfoDto groupBuy;
        private MetaDto meta;
    }

    @Data
    public static class GroupBuyDto {
        private Long activityId;
        private GroupBuyInfoDto groupBuy;
        private List<SkuDto> skus;
        private MetaDto meta;
    }

    @Data
    public static class SkuDto {
        private String code;
        private String name;
        private BigDecimal price;
        private Long baseQuota;
        private String groupGoodsId;
        private Long groupActivityId;
        private BigDecimal groupPayPrice;
        private BigDecimal groupDeductionPrice;
        private BigDecimal groupOriginalPrice;
        private Integer groupActivityType;
        private List<GroupBuyTierDto> groupTiers;
    }

    @Data
    public static class GroupBuyInfoDto {
        private Boolean unavailable;
        private Long activityId;
        private Integer activityType;
        private GroupBuyGoodsDto goods;
        private List<GroupBuyTierDto> tiers;
        private List<GroupBuyTeamDto> teamList;
        private GroupBuyStatisticDto teamStatistic;
    }

    @Data
    public static class GroupBuyGoodsDto {
        private String goodsId;
        private BigDecimal originalPrice;
        private BigDecimal deductionPrice;
        private BigDecimal payPrice;
    }

    @Data
    public static class GroupBuyTierDto {
        private Integer tierNo;
        private String tierName;
        private Integer targetCount;
        private Long bonusQuota;
    }

    @Data
    public static class GroupBuyTeamDto {
        private String userId;
        private String teamId;
        private Long activityId;
        private Integer targetCount;
        private Integer completeCount;
        private Integer lockCount;
        private String validStartTime;
        private String validEndTime;
        private String validTimeCountdown;
        private Integer reachedTierNo;
        private Integer nextTierTargetCount;
        private Integer maxTierTargetCount;
        private List<GroupBuyTierDto> tiers;
    }

    @Data
    public static class GroupBuyStatisticDto {
        private Integer allTeamCount;
        private Integer allTeamCompleteCount;
        private Integer allTeamUserCount;
    }

    @Data
    public static class OrdersDto {
        private List<OrderItemDto> items;
        private MetaDto meta;
    }

    @Data
    public static class OrderItemDto {
        private String orderId;
        private String status;
        private String displayStatus;
        private String productName;
        private BigDecimal amount;
        private String paidAt;
        private String groupStatus;
        private Integer marketType;
        /** Never propagated to the Agent model; only converted to a boolean CTA hint. */
        private String payUrl;
    }
}
