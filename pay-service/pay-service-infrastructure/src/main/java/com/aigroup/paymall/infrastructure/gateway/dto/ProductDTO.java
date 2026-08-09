package com.aigroup.paymall.infrastructure.gateway.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductDTO {

    /** 商品ID */
    private String productId;
    /** 商品名称 */
    private String productName;
    /** 商品描述 */
    private String productDesc;
    /** 商品价格 */
    private BigDecimal price;
    /** 服务端目录中的额度包编码 */
    private String productCode;
    /** 服务端目录中的基础额度（整额度点） */
    private Long baseQuota;

}
