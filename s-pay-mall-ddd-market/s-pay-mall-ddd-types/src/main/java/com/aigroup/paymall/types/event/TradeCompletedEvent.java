package com.aigroup.paymall.types.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeCompletedEvent {

    private String eventId;
    private String eventType;
    private Long userId;
    private String orderId;
    private String productCode;

}
