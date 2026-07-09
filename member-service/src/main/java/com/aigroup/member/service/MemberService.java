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

    /**
     * 查询超时仍 PENDING 的僵尸冻结ID（进程崩溃/重启导致 confirm/release 丢失），供兜底释放任务逐个释放。
     */
    List<String> listExpiredPendingFreezeIds(int timeoutMinutes, int batchLimit);

    void handleBenefitEvent(TradeCompletedEvent event);

    int grantMonthlyQuota();

    void adminAdjustQuota(Long userId, int periodDelta, int topupDelta, String remark);

    /**
     * Benefit grant status for an order: PENDING, GRANTED, REVOKED.
     */
    String benefitGrantStatusForOrder(String orderId);
}
