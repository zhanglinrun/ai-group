package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.QuotaFreezeStatusVO;
import com.aigroup.member.vo.QuotaLedgerVO;
import com.aigroup.member.vo.SkuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/api/member/skus")
    public Result<List<SkuVO>> listSkus() {
        return Result.success(memberService.listSkus());
    }

    @GetMapping("/internal/skus/by-goods/{goodsId}")
    public Result<SkuVO> skuByGoodsId(@PathVariable String goodsId) {
        return Result.success(memberService.findEnabledSkuByGoodsId(goodsId));
    }

    @GetMapping("/api/member/summary")
    public Result<MemberSummaryVO> summary() {
        return Result.success(memberService.summary(RequestUserContext.requireUserId()));
    }

    @GetMapping("/api/member/quota-ledger")
    public Result<List<QuotaLedgerVO>> quotaLedger() {
        return Result.success(memberService.listQuotaLedger(RequestUserContext.requireUserId()));
    }

    @PostMapping("/internal/members/init-free")
    public Result<Void> initFree(@RequestBody Map<String, Long> body) {
        memberService.initFree(body.get("userId"));
        return Result.success();
    }

    @PostMapping("/internal/member/quota/reservations")
    public Result<Map<String, Object>> createReservation(@RequestBody Map<String, Object> body) {
        Long userId = requiredLong(body, "userId");
        long amount = requiredLong(body, "amount");
        long minAmount = Long.parseLong(body.getOrDefault("minAmount", amount).toString());
        String abilityCode = String.valueOf(body.getOrDefault("abilityCode", "llm"));
        String ownerService = String.valueOf(body.getOrDefault("ownerService", "legacy"));
        Object requestIdObj = body.get("requestId");
        String requestId = requestIdObj != null ? requestIdObj.toString() : null;
        Object traceIdObj = body.get("traceId");
        String traceId = traceIdObj != null ? traceIdObj.toString() : null;
        return Result.success(memberService.freeze(
                userId, amount, minAmount, abilityCode, requestId, ownerService, traceId));
    }

    @PostMapping("/internal/member/quota/reservations/{reservationId}/confirm")
    public Result<QuotaFreezeStatusVO> confirmReservation(@PathVariable String reservationId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        Object actualAmount = payload.get("actualAmount");
        return Result.success(memberService.confirmWithStatus(reservationId,
                actualAmount == null ? -1L : Long.parseLong(actualAmount.toString()),
                optionalString(payload, "requestId"), optionalString(payload, "traceId")));
    }

    @PostMapping("/internal/member/quota/reservations/{reservationId}/release")
    public Result<QuotaFreezeStatusVO> releaseReservation(@PathVariable String reservationId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return Result.success(memberService.releaseWithStatus(reservationId,
                optionalString(payload, "requestId"), optionalString(payload, "traceId")));
    }

    @GetMapping("/internal/member/quota/reservations/by-request")
    public Result<QuotaFreezeStatusVO> reservationStatusByRequest(
            @RequestParam Long userId,
            @RequestParam String requestId,
            @RequestParam(required = false) String traceId) {
        return Result.success(memberService.queryFreezeByRequest(userId, requestId, traceId));
    }

    @GetMapping("/internal/member/quota/reservations/{reservationId}")
    public Result<QuotaFreezeStatusVO> reservationStatus(@PathVariable String reservationId,
                                                          @RequestParam(required = false) String requestId,
                                                          @RequestParam(required = false) String traceId) {
        return Result.success(memberService.queryFreeze(reservationId, requestId, traceId));
    }

    @GetMapping("/internal/member/quota/{userId}")
    public Result<MemberSummaryVO> quota(@PathVariable Long userId) {
        return Result.success(memberService.summary(userId));
    }

    @GetMapping("/internal/benefits/orders/{orderId}/status")
    public Result<Map<String, String>> benefitStatus(@PathVariable String orderId) {
        return Result.success(Map.of("status", memberService.benefitGrantStatusForOrder(orderId)));
    }

    private Long requiredLong(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new com.aigroup.common.exception.BusinessException(key + " is required");
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String optionalString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
