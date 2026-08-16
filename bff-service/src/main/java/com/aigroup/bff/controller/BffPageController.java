package com.aigroup.bff.controller;

import com.aigroup.bff.client.MemberFeignClient;
import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Typed page contracts for ai-group screens.
 *
 * The legacy coordinator is kept as a compatibility adapter for old commerce
 * callbacks, but browser-facing pages use these stable DTOs rather than leaking
 * a Map-shaped domain response.
 */
@RestController
@RequestMapping("/api/bff")
@RequiredArgsConstructor
public class BffPageController {

    private final MemberFeignClient memberClient;

    @GetMapping("/home")
    public Result<HomePageResponse> home() {
        return Result.success(new HomePageResponse(account(), pricingCards()));
    }

    @GetMapping("/account/overview")
    public Result<AccountOverview> accountOverview() {
        return Result.success(account());
    }

    @GetMapping("/group-activities")
    public Result<List<PricingCard>> groupActivities() {
        return Result.success(pricingCards());
    }

    private AccountOverview account() {
        requireUserId();
        Result<Map<String, Object>> result = memberClient.summary();
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            throw new BusinessException(result == null ? "积分账户暂不可用" : result.getMessage());
        }
        Map<String, Object> data = result.getData();
        return new AccountOverview(
                number(data.get("userId")),
                number(data.get("freeQuotaBalance")),
                number(data.get("paidQuotaBalance")),
                number(data.get("frozenBalance")),
                number(data.get("availableQuota")));
    }

    private List<PricingCard> pricingCards() {
        requireUserId();
        Result<List<Map<String, Object>>> result = memberClient.listSkus();
        if (result == null || result.getCode() == null || result.getCode() != 200 || result.getData() == null) {
            return List.of();
        }
        return result.getData().stream().map(item -> new PricingCard(
                string(item.get("code")),
                string(item.get("name")),
                decimal(item.get("price")),
                number(item.get("baseQuota")),
                string(item.get("groupGoodsId")),
                number(item.get("groupActivityId")))).toList();
    }

    private static Long number(Object value) {
        if (value == null) return null;
        return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private static Long requireUserId() {
        return RequestUserContext.requireUserId();
    }

    public record HomePageResponse(AccountOverview account, List<PricingCard> pricing) {}

    public record AccountOverview(Long userId, Long freeQuotaBalance, Long paidQuotaBalance,
                                  Long frozenBalance, Long availableQuota) {}

    public record PricingCard(String code, String name, BigDecimal price, Long baseQuota,
                              String groupGoodsId, Long groupActivityId) {}
}
