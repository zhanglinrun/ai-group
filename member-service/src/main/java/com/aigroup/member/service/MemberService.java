package com.aigroup.member.service;

import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.SkuVO;

import java.util.List;
import java.util.Map;

public interface MemberService {

    void initFree(Long userId);

    List<SkuVO> listSkus();

    MemberSummaryVO summary(Long userId);

    Map<String, String> freeze(Long userId, String abilityCode, int multiplier, String requestId);

    void confirm(String freezeId);

    void release(String freezeId);

    void handleBenefitEvent(TradeCompletedEvent event);

    int grantMonthlyQuota();

    void adminAdjustQuota(Long userId, int periodDelta, int topupDelta, String remark);

    /**
     * Benefit grant status for an order: PENDING, GRANTED, REVOKED.
     */
    String benefitGrantStatusForOrder(String orderId);
}
