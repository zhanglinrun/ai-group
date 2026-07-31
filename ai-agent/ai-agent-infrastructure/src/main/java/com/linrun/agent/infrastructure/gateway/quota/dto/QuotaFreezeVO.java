package com.linrun.agent.infrastructure.gateway.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaFreezeVO {

    private String freezeId;
    private Long amount;
    private Long userId;
    private Long settledAmount;
    private Long requestedAmount;
    private Long minAmount;
    private String abilityCode;
    private String status;
    private String requestId;
    private String traceId;
    private String requestFingerprint;
    private String ownerService;
}
