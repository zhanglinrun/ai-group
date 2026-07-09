package com.aigroup.member.dto;

import lombok.Data;

@Data
public class TradeCompletedEvent {
    private String eventId;
    private String eventType;
    private Long userId;
    private String orderId;
    private String productCode;
}
