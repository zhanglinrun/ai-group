package com.aigroup.member.dto;

import lombok.Data;

@Data
public class TradeCompletedEvent {
    private String eventId;
    private String eventType;
    private Long userId;
    private String orderId;
    private String productCode;
    /** 阶梯拼团加赠额度（在基础额度之上叠加发放；直购/经典为 0/空） */
    private Integer bonusQuota;
}
