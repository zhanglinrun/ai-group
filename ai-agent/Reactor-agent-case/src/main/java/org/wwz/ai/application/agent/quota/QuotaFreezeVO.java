package org.wwz.ai.application.agent.quota;

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
    private Integer amount;
}
