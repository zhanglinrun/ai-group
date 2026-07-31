package com.aigroup.member.service;

import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.QuotaLedgerVO;
import com.aigroup.member.vo.QuotaFreezeStatusVO;
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

    Map<String, Object> freeze(Long userId, long requestedAmount, long minAmount,
                               String abilityCode, String requestId, String ownerService);

    /** Immutable trace correlation for a service-owned reservation and its recovery. */
    Map<String, Object> freeze(Long userId, long requestedAmount, long minAmount,
                               String abilityCode, String requestId, String ownerService, String traceId);

    void confirm(String freezeId, long actualAmount);

    void confirm(String freezeId);

    void release(String freezeId);

    QuotaFreezeStatusVO confirmWithStatus(String freezeId, long actualAmount);

    QuotaFreezeStatusVO confirmWithStatus(String freezeId, long actualAmount,
                                          String requestId, String traceId);

    QuotaFreezeStatusVO releaseWithStatus(String freezeId);

    QuotaFreezeStatusVO releaseWithStatus(String freezeId, String requestId, String traceId);

    QuotaFreezeStatusVO queryFreeze(String freezeId);

    QuotaFreezeStatusVO queryFreeze(String freezeId, String requestId, String traceId);

    QuotaFreezeStatusVO queryFreezeByRequest(Long userId, String requestId);

    QuotaFreezeStatusVO queryFreezeByRequest(Long userId, String requestId, String traceId);

    /** Query legacy/unmanaged stale freezes that member may safely release. */
    List<String> listExpiredPendingFreezeIds(int timeoutMinutes, int batchLimit);

    /** Managed freezes are alerted for reconciliation but never blindly released. */
    List<String> listExpiredManagedPendingFreezeIds(int timeoutMinutes, int batchLimit);

    void handleBenefitEvent(TradeCompletedEvent event);

    int grantMonthlyQuota();

    /** paidDelta is expressed in whole credits; ledger/account persistence uses microcredits. */
    void adminAdjustQuota(Long userId, long paidDelta, String remark);

    /**
     * Benefit grant status for an order: PENDING, GRANTED, REVOKED.
     */
    String benefitGrantStatusForOrder(String orderId);
}
