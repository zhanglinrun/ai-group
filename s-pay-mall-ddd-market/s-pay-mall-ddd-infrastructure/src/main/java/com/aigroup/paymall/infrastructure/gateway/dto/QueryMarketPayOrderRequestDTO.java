package com.aigroup.paymall.infrastructure.gateway.dto;

import lombok.Data;

/**
 * Exact group-lock business key used after an ambiguous transport result.
 */
@Data
public class QueryMarketPayOrderRequestDTO {

    private String userId;
    private String source;
    private String channel;
    private String outTradeNo;
}
