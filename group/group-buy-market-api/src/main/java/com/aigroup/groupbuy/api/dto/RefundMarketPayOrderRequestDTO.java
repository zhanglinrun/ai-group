package com.aigroup.groupbuy.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 营销拼团退单请求对象
 * @create 2025-01-01 00:00
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RefundMarketPayOrderRequestDTO {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 外部交易单号
     */
    private String outTradeNo;

    /**
     * 渠道
     */
    private String source;

    /**
     * 来源
     */
    private String channel;

}