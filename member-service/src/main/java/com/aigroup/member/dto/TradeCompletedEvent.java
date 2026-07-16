package com.aigroup.member.dto;

import lombok.Data;

@Data
public class TradeCompletedEvent {
    private String eventId;
    private String eventType;
    private Long userId;
    private String orderId;
    private String productCode;
    /** Trusted order snapshot, in whole credits. */
    private Long baseQuota;
    /** Trusted group-tier snapshot bonus, in whole credits. */
    private Long bonusQuota;
}
