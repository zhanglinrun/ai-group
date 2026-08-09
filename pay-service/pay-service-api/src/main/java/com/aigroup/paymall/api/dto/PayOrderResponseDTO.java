package com.aigroup.paymall.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** Stable payment order projection exposed by the canonical commerce contract. */
@Data
public class PayOrderResponseDTO {
    private String orderId;
    private String userId;
    private String productCode;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private String status;
    private String payUrl;
    private Date orderTime;
    private Date payTime;
    private Long groupActivityId;
    private String groupTeamId;
    /** Local dev profile exposes a guarded simulated payment endpoint. */
    private boolean demoCompletionEnabled;
}
