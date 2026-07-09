package com.aigroup.member.controller;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.common.model.Result;
import com.aigroup.member.entity.BenefitGrantEvent;
import com.aigroup.member.entity.ProductSku;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/member/admin")
@RequiredArgsConstructor
public class MemberAdminController {

    private final MemberService memberService;
    private final BenefitGrantEventMapper benefitGrantEventMapper;
    private final ProductSkuMapper productSkuMapper;

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

    /** 运营端：全量 SKU（含下架），供套餐/价格管理 */
    @GetMapping("/skus")
    public Result<List<ProductSku>> listAllSkus() {
        requireAdmin();
        return Result.success(productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().orderByAsc(ProductSku::getId)));
    }

    /** 运营端：按 code 更新 SKU（价格/配额/有效期/状态/拼团映射；null 字段不更新） */
    @PutMapping("/skus/{code}")
    public Result<ProductSku> updateSku(@PathVariable String code, @RequestBody Map<String, Object> body) {
        requireAdmin();
        ProductSku sku = productSkuMapper.selectOne(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getCode, code));
        if (sku == null) {
            throw new BusinessException("sku not found: " + code);
        }
        if (body.get("name") != null) {
            sku.setName(String.valueOf(body.get("name")));
        }
        if (body.get("price") != null) {
            sku.setPrice(new BigDecimal(String.valueOf(body.get("price"))));
        }
        if (body.get("periodQuota") != null) {
            sku.setPeriodQuota(Integer.valueOf(String.valueOf(body.get("periodQuota"))));
        }
        if (body.get("topupQuota") != null) {
            sku.setTopupQuota(Integer.valueOf(String.valueOf(body.get("topupQuota"))));
        }
        if (body.get("memberDays") != null) {
            sku.setMemberDays(Integer.valueOf(String.valueOf(body.get("memberDays"))));
        }
        if (body.get("status") != null) {
            sku.setStatus(Integer.valueOf(String.valueOf(body.get("status"))));
        }
        if (body.containsKey("groupGoodsId")) {
            Object goodsId = body.get("groupGoodsId");
            sku.setGroupGoodsId(goodsId == null || String.valueOf(goodsId).isBlank() ? null : String.valueOf(goodsId));
        }
        if (body.containsKey("groupActivityId")) {
            Object activityId = body.get("groupActivityId");
            sku.setGroupActivityId(activityId == null || String.valueOf(activityId).isBlank()
                    ? null : Long.valueOf(String.valueOf(activityId)));
        }
        productSkuMapper.updateById(sku);
        return Result.success(sku);
    }

    private void requireAdmin() {
        String role = RequestUserContext.getRole();
        if (role == null || !"ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("admin role required");
        }
    }
}
