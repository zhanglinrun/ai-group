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
    /** 阶梯拼团加赠额度（在 SKU 基础额度之上叠加发放；直购/经典为 0/空） */
    private Integer bonusQuota;

}
