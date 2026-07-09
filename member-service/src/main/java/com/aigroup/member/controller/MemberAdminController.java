package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.common.model.Result;
import com.aigroup.member.entity.BenefitGrantEvent;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
@RequestMapping("/api/member/admin")
@RequiredArgsConstructor
public class MemberAdminController {

    private final MemberService memberService;
    private final BenefitGrantEventMapper benefitGrantEventMapper;

    @GetMapping("/members/{userId}")
    public Result<MemberSummaryVO> memberDetail(@PathVariable Long userId) {
        requireAdmin();
        return Result.success(memberService.summary(userId));
    }

    @GetMapping("/benefit-events")
    public Result<List<BenefitGrantEvent>> benefitEvents() {
        requireAdmin();
        return Result.success(benefitGrantEventMapper.selectList(
                new LambdaQueryWrapper<BenefitGrantEvent>().orderByDesc(BenefitGrantEvent::getCreatedAt).last("LIMIT 100")));
    }

    @PostMapping("/quota/adjust")
    public Result<Void> adjustQuota(@RequestBody Map<String, Object> body) {
        requireAdmin();
        Long userId = Long.valueOf(body.get("userId").toString());
        int periodDelta = Integer.parseInt(body.getOrDefault("periodDelta", 0).toString());
        int topupDelta = Integer.parseInt(body.getOrDefault("topupDelta", 0).toString());
        String remark = body.getOrDefault("remark", "admin adjust").toString();
        memberService.adminAdjustQuota(userId, periodDelta, topupDelta, remark);
        return Result.success();
    }

    @PostMapping("/jobs/monthly-grant")
    public Result<Map<String, Integer>> triggerMonthlyGrant() {
        requireAdmin();
        int count = memberService.grantMonthlyQuota();
        return Result.success(Map.of("grantedCount", count));
    }

    private void requireAdmin() {
        String role = RequestUserContext.getRole();
        if (role == null || !"ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("admin role required");
        }
    }
}
