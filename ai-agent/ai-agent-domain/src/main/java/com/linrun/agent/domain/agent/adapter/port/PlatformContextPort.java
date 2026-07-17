package com.linrun.agent.domain.agent.adapter.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only typed boundary from the Agent domain to the platform BFF.
 *
 * <p>The authenticated owner is always supplied by {@code AgentContext}; it is
 * deliberately not part of any model-visible tool input.</p>
 */
public interface PlatformContextPort {

    ContextResult<AccountSummary> accountSummary(Long ownerId);

    ContextResult<Pricing> pricing(Long ownerId);

    ContextResult<GroupBuy> groupBuy(Long ownerId, Long activityId);

    ContextResult<Orders> orders(Long ownerId);

    record ContextResult<T>(T data, BffMeta meta) {
        public ContextResult {
            if (data == null) {
                throw new IllegalArgumentException("platform context data must not be null");
            }
            if (meta == null) {
                throw new IllegalArgumentException("platform context meta must not be null");
            }
        }
    }

    record BffMeta(boolean degraded, List<Degradation> errors) {
        public BffMeta {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    record Degradation(String service, String code, String message) {
    }

    record AccountSummary(Long freeQuotaBalance,
                          Long paidQuotaBalance,
                          Long frozenBalance,
                          Long availableQuota,
                          List<QuotaLedgerEntry> quotaLedger,
                          List<PendingGroupOrder> pendingGroupOrders) {
        public AccountSummary {
            quotaLedger = immutable(quotaLedger);
            pendingGroupOrders = immutable(pendingGroupOrders);
        }
    }

    record QuotaLedgerEntry(Long id,
                            String type,
                            Long amount,
                            String freezeId,
                            String abilityCode,
                            String remark,
                            String createdAt) {
    }

    record PendingGroupOrder(String orderId,
                             String status,
                             String productName,
                             String paidAt) {
    }

    record Pricing(List<Sku> skus, GroupBuyInfo groupBuy) {
        public Pricing {
            skus = immutable(skus);
        }
    }

    record GroupBuy(Long activityId, GroupBuyInfo groupBuy, List<Sku> skus) {
        public GroupBuy {
            skus = immutable(skus);
        }
    }

    record Sku(String code,
               String name,
               BigDecimal price,
               Long baseQuota,
               String groupGoodsId,
               Long groupActivityId,
               BigDecimal groupPayPrice,
               BigDecimal groupDeductionPrice,
               BigDecimal groupOriginalPrice,
               Integer groupActivityType,
               List<GroupBuyTier> groupTiers) {
        public Sku {
            groupTiers = immutable(groupTiers);
        }
    }

    record GroupBuyInfo(boolean unavailable,
                        Long activityId,
                        Integer activityType,
                        GroupBuyGoods goods,
                        List<GroupBuyTier> tiers,
                        List<GroupBuyTeam> teamList,
                        GroupBuyStatistic teamStatistic) {
        public GroupBuyInfo {
            tiers = immutable(tiers);
            teamList = immutable(teamList);
        }
    }

    record GroupBuyGoods(String goodsId,
                         BigDecimal originalPrice,
                         BigDecimal deductionPrice,
                         BigDecimal payPrice) {
    }

    record GroupBuyTier(Integer tierNo,
                        String tierName,
                        Integer targetCount,
                        Long bonusQuota) {
    }

    record GroupBuyTeam(String teamId,
                        Long activityId,
                        Integer targetCount,
                        Integer completeCount,
                        Integer lockCount,
                        String validStartTime,
                        String validEndTime,
                        String validTimeCountdown,
                        Integer reachedTierNo,
                        Integer nextTierTargetCount,
                        Integer maxTierTargetCount,
                        List<GroupBuyTier> tiers) {
        public GroupBuyTeam {
            tiers = immutable(tiers);
        }
    }

    record GroupBuyStatistic(Integer allTeamCount,
                             Integer allTeamCompleteCount,
                             Integer allTeamUserCount) {
    }

    record Orders(List<OrderItem> items) {
        public Orders {
            items = immutable(items);
        }
    }

    record OrderItem(String orderId,
                     String status,
                     String displayStatus,
                     String productName,
                     BigDecimal amount,
                     String paidAt,
                     String groupStatus,
                     Integer marketType,
                     boolean paymentActionAvailable) {
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
