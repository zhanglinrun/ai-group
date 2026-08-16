package com.aigroup.paymall.infrastructure.gateway.dto;

import lombok.Data;

/**
 * @description 获取微信登录二维码响应对象
 * @create 2024-02-25 09:36
 */
@Data
public class WeixinQrCodeResponseDTO {

    private String ticket;
    private Long expire_seconds;
    private String url;

}
