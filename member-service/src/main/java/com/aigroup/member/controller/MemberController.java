package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
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
        return Result.success(memberService.summary(RequestUserContext.getUserId()));
    }

    @GetMapping("/api/member/quota-ledger")
    public Result<List<QuotaLedgerVO>> quotaLedger() {
        return Result.success(memberService.listQuotaLedger(RequestUserContext.getUserId()));
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
        Object requestIdObj = body.get("requestId");
        String requestId = requestIdObj != null ? requestIdObj.toString() : null;
        return Result.success(memberService.freeze(userId, amount, minAmount, abilityCode, requestId));
    }

    @PostMapping("/internal/quota/confirm")
    public Result<Void> confirm(@RequestBody Map<String, Object> body) {
        String freezeId = requiredString(body, "freezeId");
        Object actualAmount = body.get("actualAmount");
        memberService.confirm(freezeId,
                actualAmount == null ? -1L : Long.parseLong(actualAmount.toString()));
        return Result.success();
    }

    @PostMapping("/internal/quota/release")
    public Result<Void> release(@RequestBody Map<String, Object> body) {
        memberService.release(requiredString(body, "freezeId"));
        return Result.success();
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
}
