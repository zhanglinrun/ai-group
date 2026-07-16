package com.aigroup.groupbuy.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Dev-only request to finalize the real team of an already-paid market order. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoFinalizeMarketPayOrderRequestDTO {
    private String userId;
    private String outTradeNo;
}
