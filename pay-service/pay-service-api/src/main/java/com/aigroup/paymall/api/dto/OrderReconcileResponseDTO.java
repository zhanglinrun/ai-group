package com.aigroup.paymall.api.dto;

import lombok.Data;

/** Result of an internal payment-provider reconciliation attempt. */
@Data
public class OrderReconcileResponseDTO {

    private String orderId;
    private boolean recovered;
    private String status;
}
