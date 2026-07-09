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
    /** 客户端幂等键（agent 请求ID）；member 侧据此对同一请求的重复预扣返回同一 freezeId */
    private String requestId;
}
