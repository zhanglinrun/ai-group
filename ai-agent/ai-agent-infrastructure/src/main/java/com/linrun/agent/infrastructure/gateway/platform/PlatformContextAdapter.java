package com.linrun.agent.infrastructure.gateway.platform;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.infrastructure.gateway.platform.dto.PlatformBffDtos;
import com.linrun.agent.infrastructure.gateway.platform.dto.PlatformBffResult;

import java.util.List;

/** Maps the BFF wire contract into domain-owned read models. */
@Component
@RequiredArgsConstructor
public class PlatformContextAdapter implements PlatformContextPort {

    private static final int SUCCESS_CODE = 200;

    private final PlatformBffClient client;

    @Override
    public ContextResult<AccountSummary> accountSummary(Long ownerId) {
        PlatformBffDtos.AccountSummaryDto dto = requireData(
                client.accountSummary(requireOwnerId(ownerId)), "account_summary");
        List<PlatformBffDtos.QuotaLedgerEntryDto> quotaLedger = requireList(
                dto.getQuotaLedger(), "account_summary.quotaLedger");
        List<PlatformBffDtos.PendingGroupOrderDto> pendingOrders = requireList(
                dto.getPendingGroupOrders(), "account_summary.pendingGroupOrders");
        return new ContextResult<>(new AccountSummary(
                dto.getFreeQuotaBalance(),
                dto.getPaidQuotaBalance(),
                dto.getFrozenBalance(),
                dto.getAvailableQuota(),
                quotaLedger.stream().map(this::mapQuotaLedger).toList(),
                pendingOrders.stream().map(this::mapPendingOrder).toList()),
                mapMeta(dto.getMeta(), "account_summary"));
    }

    @Override
    public ContextResult<Pricing> pricing(Long ownerId) {
        PlatformBffDtos.PricingDto dto = requireData(
                client.pricing(requireOwnerId(ownerId)), "pricing");
        List<PlatformBffDtos.SkuDto> skus = requireList(dto.getSkus(), "pricing.skus");
        return new ContextResult<>(new Pricing(
                skus.stream().map(this::mapSku).toList(),
                mapGroupBuyInfo(dto.getGroupBuy())),
                mapMeta(dto.getMeta(), "pricing"));
    }

    @Override
    public ContextResult<GroupBuy> groupBuy(Long ownerId, Long activityId) {
        Long trustedOwnerId = requireOwnerId(ownerId);
        if (activityId == null) {
            PlatformBffDtos.PricingDto dto = requireData(client.pricing(trustedOwnerId), "group_buy");
            List<PlatformBffDtos.SkuDto> skus = requireList(dto.getSkus(), "group_buy.skus");
            GroupBuyInfo groupBuyInfo = mapGroupBuyInfo(dto.getGroupBuy());
            return new ContextResult<>(new GroupBuy(
                    groupBuyInfo == null ? null : groupBuyInfo.activityId(),
                    groupBuyInfo,
                    skus.stream().map(this::mapSku).toList()),
                    mapMeta(dto.getMeta(), "group_buy"));
        }
        if (activityId <= 0L) {
            throw new IllegalArgumentException("activityId must be positive");
        }
        PlatformBffDtos.GroupBuyDto dto = requireData(
                client.groupBuy(trustedOwnerId, activityId), "group_buy");
        List<PlatformBffDtos.SkuDto> skus = requireList(dto.getSkus(), "group_buy.skus");
        return new ContextResult<>(new GroupBuy(
                dto.getActivityId(),
                mapGroupBuyInfo(dto.getGroupBuy()),
                skus.stream().map(this::mapSku).toList()),
                mapMeta(dto.getMeta(), "group_buy"));
    }

    @Override
    public ContextResult<Orders> orders(Long ownerId) {
        PlatformBffDtos.OrdersDto dto = requireData(
                client.orders(requireOwnerId(ownerId)), "orders");
        List<PlatformBffDtos.OrderItemDto> items = requireList(dto.getItems(), "orders.items");
        return new ContextResult<>(new Orders(items.stream().map(this::mapOrder).toList()),
                mapMeta(dto.getMeta(), "orders"));
    }

    private QuotaLedgerEntry mapQuotaLedger(PlatformBffDtos.QuotaLedgerEntryDto dto) {
        return new QuotaLedgerEntry(dto.getId(), dto.getType(), dto.getAmount(), dto.getFreezeId(),
                dto.getAbilityCode(), dto.getRemark(), dto.getCreatedAt());
    }

    private PendingGroupOrder mapPendingOrder(PlatformBffDtos.PendingGroupOrderDto dto) {
        return new PendingGroupOrder(dto.getOrderId(), dto.getStatus(), dto.getProductName(), dto.getPaidAt());
    }

    private Sku mapSku(PlatformBffDtos.SkuDto dto) {
        List<GroupBuyTier> tiers = safeList(dto.getGroupTiers()).stream().map(this::mapTier).toList();
        return new Sku(dto.getCode(), dto.getName(), dto.getPrice(), dto.getBaseQuota(),
                dto.getGroupGoodsId(), dto.getGroupActivityId(), dto.getGroupPayPrice(),
                dto.getGroupDeductionPrice(), dto.getGroupOriginalPrice(), dto.getGroupActivityType(), tiers);
    }

    private GroupBuyInfo mapGroupBuyInfo(PlatformBffDtos.GroupBuyInfoDto dto) {
        if (dto == null) {
            return null;
        }
        return new GroupBuyInfo(Boolean.TRUE.equals(dto.getUnavailable()), dto.getActivityId(),
                dto.getActivityType(), mapGoods(dto.getGoods()),
                safeList(dto.getTiers()).stream().map(this::mapTier).toList(),
                safeList(dto.getTeamList()).stream().map(this::mapTeam).toList(),
                mapStatistic(dto.getTeamStatistic()));
    }

    private GroupBuyGoods mapGoods(PlatformBffDtos.GroupBuyGoodsDto dto) {
        return dto == null ? null : new GroupBuyGoods(
                dto.getGoodsId(), dto.getOriginalPrice(), dto.getDeductionPrice(), dto.getPayPrice());
    }

    private GroupBuyTier mapTier(PlatformBffDtos.GroupBuyTierDto dto) {
        return new GroupBuyTier(dto.getTierNo(), dto.getTierName(), dto.getTargetCount(), dto.getBonusQuota());
    }

    private GroupBuyTeam mapTeam(PlatformBffDtos.GroupBuyTeamDto dto) {
        return new GroupBuyTeam(dto.getTeamId(), dto.getActivityId(),
                dto.getTargetCount(), dto.getCompleteCount(), dto.getLockCount(), dto.getValidStartTime(),
                dto.getValidEndTime(), dto.getValidTimeCountdown(), dto.getReachedTierNo(),
                dto.getNextTierTargetCount(), dto.getMaxTierTargetCount(),
                safeList(dto.getTiers()).stream().map(this::mapTier).toList());
    }

    private GroupBuyStatistic mapStatistic(PlatformBffDtos.GroupBuyStatisticDto dto) {
        return dto == null ? null : new GroupBuyStatistic(
                dto.getAllTeamCount(), dto.getAllTeamCompleteCount(), dto.getAllTeamUserCount());
    }

    private OrderItem mapOrder(PlatformBffDtos.OrderItemDto dto) {
        // The raw payment form is intentionally discarded. The model gets only
        // a boolean and the tool-level /orders navigation CTA.
        return new OrderItem(dto.getOrderId(), dto.getStatus(), dto.getDisplayStatus(),
                dto.getProductName(), dto.getAmount(), dto.getPaidAt(), dto.getGroupStatus(),
                dto.getMarketType(), StringUtils.isNotBlank(dto.getPayUrl()));
    }

    private BffMeta mapMeta(PlatformBffDtos.MetaDto dto, String operation) {
        if (dto == null || dto.getDegraded() == null) {
            throw new IllegalStateException(operation + " response is missing BFF degradation metadata");
        }
        return new BffMeta(Boolean.TRUE.equals(dto.getDegraded()), safeList(dto.getErrors()).stream()
                .map(error -> new Degradation(error.getService(), error.getCode(), error.getMessage()))
                .toList());
    }

    private Long requireOwnerId(Long ownerId) {
        if (ownerId == null || ownerId <= 0L) {
            throw new IllegalArgumentException("trusted ownerId is required");
        }
        return ownerId;
    }

    private <T> T requireData(PlatformBffResult<T> result, String operation) {
        if (result == null) {
            throw new IllegalStateException(operation + " BFF returned no response");
        }
        if (!Integer.valueOf(SUCCESS_CODE).equals(result.getCode())) {
            throw new IllegalStateException(operation + " BFF request failed");
        }
        if (result.getData() == null) {
            throw new IllegalStateException(operation + " BFF response is missing data");
        }
        return result.getData();
    }

    private <T> List<T> requireList(List<T> values, String field) {
        if (values == null) {
            throw new IllegalStateException(field + " is missing");
        }
        return values;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
