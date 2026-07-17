package com.linrun.agent.infrastructure.gateway.quota.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaFreezeActionRequest {

    private String freezeId;
    private Long actualAmount;
}
