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
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping("/internal/quota/freeze")
    public Result<Map<String, Object>> freeze(@RequestBody Map<String, Object> body) {
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

    @PostMapping("/internal/quota/confirm")
    public Result<QuotaFreezeStatusVO> confirm(@RequestBody Map<String, Object> body) {
        String freezeId = requiredString(body, "freezeId");
        Object actualAmount = body.get("actualAmount");
        return Result.success(memberService.confirmWithStatus(freezeId,
                actualAmount == null ? -1L : Long.parseLong(actualAmount.toString()),
                optionalString(body, "requestId"), optionalString(body, "traceId")));
    }

    @PostMapping("/internal/quota/release")
    public Result<QuotaFreezeStatusVO> release(@RequestBody Map<String, Object> body) {
        return Result.success(memberService.releaseWithStatus(requiredString(body, "freezeId"),
                optionalString(body, "requestId"), optionalString(body, "traceId")));
    }

    @GetMapping("/internal/quota/freezes/{freezeId}")
    public Result<QuotaFreezeStatusVO> freezeStatus(@PathVariable String freezeId,
                                                     @org.springframework.web.bind.annotation.RequestParam(required = false) String requestId,
                                                     @org.springframework.web.bind.annotation.RequestParam(required = false) String traceId) {
        return Result.success(memberService.queryFreeze(freezeId, requestId, traceId));
    }

    @GetMapping("/internal/quota/freezes/by-request")
    public Result<QuotaFreezeStatusVO> freezeStatusByRequest(
            @org.springframework.web.bind.annotation.RequestParam Long userId,
            @org.springframework.web.bind.annotation.RequestParam String requestId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String traceId) {
        return Result.success(memberService.queryFreezeByRequest(userId, requestId, traceId));
    }

    @GetMapping("/internal/benefits/orders/{orderId}/status")
    public Result<Map<String, String>> benefitStatus(@org.springframework.web.bind.annotation.PathVariable String orderId) {
        return Result.success(Map.of("status", memberService.benefitGrantStatusForOrder(orderId)));
    }

    private Long requiredLong(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new com.aigroup.common.exception.BusinessException(key + " is required");
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String requiredString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new com.aigroup.common.exception.BusinessException(key + " is required");
        }
        return String.valueOf(value).trim();
    }

    private String optionalString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
