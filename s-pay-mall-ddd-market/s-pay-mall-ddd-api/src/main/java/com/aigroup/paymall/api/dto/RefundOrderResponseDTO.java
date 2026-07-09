package com.aigroup.paymall.api.dto;

import lombok.Data;

/**
 * 退单响应DTO
 */
@Data
public class RefundOrderResponseDTO {

    /** 退单是否成功 */
    private Boolean success;
    
    /** 退单消息 */
    private String message;
    
    /** 订单号 */
    private String orderId;
    
}