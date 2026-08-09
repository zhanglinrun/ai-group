package com.aigroup.groupbuy.api.dto;

import lombok.Data;

/**
 * Exact business key used to resolve an ambiguous lock result.
 */
@Data
public class QueryMarketPayOrderRequestDTO {

    private String userId;
    private String source;
    private String channel;
    private String outTradeNo;
}
