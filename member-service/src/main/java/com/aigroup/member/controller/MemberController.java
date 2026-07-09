package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.model.Result;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.SkuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/api/member/summary")
    public Result<MemberSummaryVO> summary() {
        return Result.success(memberService.summary(RequestUserContext.getUserId()));
    }

    @PostMapping("/internal/members/init-free")
    public Result<Void> initFree(@RequestBody Map<String, Long> body) {
        memberService.initFree(body.get("userId"));
        return Result.success();
    }

    @PostMapping("/internal/quota/freeze")
    public Result<Map<String, String>> freeze(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        String abilityCode = body.get("abilityCode").toString();
        int multiplier = Integer.parseInt(body.get("multiplier").toString());
        Object requestIdObj = body.get("requestId");
        String requestId = requestIdObj != null ? requestIdObj.toString() : null;
        return Result.success(memberService.freeze(userId, abilityCode, multiplier, requestId));
    }

    @PostMapping("/internal/quota/confirm")
    public Result<Void> confirm(@RequestBody Map<String, String> body) {
        memberService.confirm(body.get("freezeId"));
        return Result.success();
    }

    @PostMapping("/internal/quota/release")
    public Result<Void> release(@RequestBody Map<String, String> body) {
        memberService.release(body.get("freezeId"));
        return Result.success();
    }

    @GetMapping("/internal/benefits/orders/{orderId}/status")
    public Result<Map<String, String>> benefitStatus(@org.springframework.web.bind.annotation.PathVariable String orderId) {
        return Result.success(Map.of("status", memberService.benefitGrantStatusForOrder(orderId)));
    }
}
