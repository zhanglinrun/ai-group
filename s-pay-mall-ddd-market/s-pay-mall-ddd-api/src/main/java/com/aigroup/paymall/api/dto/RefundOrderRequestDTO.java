package com.aigroup.paymall.api.dto;

import lombok.Data;

/**
 * 退单请求DTO
 */
@Data
public class RefundOrderRequestDTO {

    /** 用户ID */
    private String userId;
    
    /** 订单号 */
    private String orderId;
    
}