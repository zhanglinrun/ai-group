package com.aigroup.member.vo;

import lombok.Data;

@Data
public class MemberSummaryVO {
    private Long userId;
    private Long freeQuotaBalance;
    private Long paidQuotaBalance;
    private Long frozenBalance;
    private Long availableQuota;
}
