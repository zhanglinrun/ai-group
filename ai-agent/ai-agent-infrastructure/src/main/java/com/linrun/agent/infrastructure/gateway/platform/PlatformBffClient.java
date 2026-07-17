package com.linrun.agent.infrastructure.gateway.platform;

import com.linrun.agent.infrastructure.gateway.platform.dto.PlatformBffDtos;
import com.linrun.agent.infrastructure.gateway.platform.dto.PlatformBffResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** Read-only typed client; intentionally declares no order/payment mutation endpoint. */
@FeignClient(
        name = "platform-bff",
        contextId = "platformBffClient",
        url = "${ai-group.bff-service.base-url:http://127.0.0.1:8083}",
        configuration = PlatformBffFeignConfiguration.class
)
public interface PlatformBffClient {

    String HEADER_USER_ID = "X-User-Id";

    @GetMapping("/api/bff/account/summary")
    PlatformBffResult<PlatformBffDtos.AccountSummaryDto> accountSummary(
            @RequestHeader(HEADER_USER_ID) Long ownerId);

    @GetMapping("/api/bff/pricing")
    PlatformBffResult<PlatformBffDtos.PricingDto> pricing(
            @RequestHeader(HEADER_USER_ID) Long ownerId);

    @GetMapping("/api/bff/group-buy/{activityId}")
    PlatformBffResult<PlatformBffDtos.GroupBuyDto> groupBuy(
            @RequestHeader(HEADER_USER_ID) Long ownerId,
            @PathVariable("activityId") Long activityId);

    @GetMapping("/api/bff/orders")
    PlatformBffResult<PlatformBffDtos.OrdersDto> orders(
            @RequestHeader(HEADER_USER_ID) Long ownerId);
}
