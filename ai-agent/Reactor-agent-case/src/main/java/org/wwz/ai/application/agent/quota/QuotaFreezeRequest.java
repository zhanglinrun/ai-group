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
    private Long amount;
    private Long minAmount;
    private String abilityCode;
    /** 客户端幂等键（单次LLM/付费工具调用ID）。 */
    private String requestId;
}
