package com.linrun.agent.domain.agent.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Agent-owned durable command for reserve and eventual confirm/release convergence. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaSettlementCommand {

    private Long id;
    private Long userId;
    private String billingRequestId;
    private String requestFingerprint;
    private String abilityCode;
    private Long requestedMicrocredits;
    private Long minimumMicrocredits;
    private String freezeId;
    private Long reservedMicrocredits;
    private QuotaSettlementIntent intendedAction;
    private Long intendedMicrocredits;
    private Long settledMicrocredits;
    private Long llmInvocationId;
    private Long inputRateSnapshot;
    private Long outputRateSnapshot;
    private Integer promptTokens;
    private Integer completionTokens;
    private String usageSource;
    private Long chargedMicrocredits;
    private QuotaSettlementState state;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime providerStartedAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastError;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
