package com.aigroup.member.service;

import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.QuotaLedgerVO;
import com.aigroup.member.vo.SkuVO;

import java.util.List;
import java.util.Map;

public interface MemberService {

    void initFree(Long userId);

    List<SkuVO> listSkus();

    SkuVO findEnabledSkuByGoodsId(String goodsId);

    MemberSummaryVO summary(Long userId);

    List<QuotaLedgerVO> listQuotaLedger(Long userId);

    Map<String, Object> freeze(Long userId, long requestedAmount, long minAmount,
                               String abilityCode, String requestId);

    void confirm(String freezeId, long actualAmount);

    void confirm(String freezeId);

    void release(String freezeId);

    /**
     * 查询超时仍 PENDING 的僵尸冻结ID（进程崩溃/重启导致 confirm/release 丢失），供兜底释放任务逐个释放。
     */
    List<String> listExpiredPendingFreezeIds(int timeoutMinutes, int batchLimit);

    void handleBenefitEvent(TradeCompletedEvent event);

    int grantMonthlyQuota();

    /** paidDelta is expressed in whole credits; ledger/account persistence uses microcredits. */
    void adminAdjustQuota(Long userId, long paidDelta, String remark);

    /**
     * Benefit grant status for an order: PENDING, GRANTED, REVOKED.
     */
    String benefitGrantStatusForOrder(String orderId);
}
