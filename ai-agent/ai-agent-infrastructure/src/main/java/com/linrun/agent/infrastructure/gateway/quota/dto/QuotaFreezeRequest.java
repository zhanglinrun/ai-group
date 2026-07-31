package com.linrun.agent.infrastructure.gateway.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaFreezeRequest {

    private Long userId;
    private Long amount;
    private Long minAmount;
    private String abilityCode;
    /** Client idempotency key for a single billable invocation. */
    private String requestId;
    /** Immutable Run trace used by Member audit and recovery lookups. */
    private String traceId;
    /** Marks ai-agent as the durable owner of confirm/release recovery. */
    private String ownerService;
}
