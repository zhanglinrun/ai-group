package org.wwz.ai.application.agent.quota;

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
    private String abilityCode;
    private Integer multiplier;
}
