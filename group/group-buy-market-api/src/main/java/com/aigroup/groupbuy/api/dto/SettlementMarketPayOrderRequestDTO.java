package com.aigroup.groupbuy.api.dto;

import lombok.Data;

import java.util.Date;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 结算请求对象
 * @create 2025-02-02 16:08
 */
@Data
public class SettlementMarketPayOrderRequestDTO {

    /** 渠道 */
    private String source;
    /** 来源 */
    private String channel;
    /** 用户ID */
    private String userId;
    /** 外部交易单号 */
    private String outTradeNo;
    /** 外部交易时间 */
    private Date outTradeTime;

}
