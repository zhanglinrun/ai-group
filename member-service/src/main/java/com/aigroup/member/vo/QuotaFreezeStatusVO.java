package com.aigroup.member.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable quota reservation state returned to service callers. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaFreezeStatusVO {

    private String freezeId;
    private Long userId;
    private Long amount;
    private Long settledAmount;
    private Long requestedAmount;
    private Long minAmount;
    private String abilityCode;
    private String status;
    private String requestId;
    private String requestFingerprint;
    private String ownerService;
}
