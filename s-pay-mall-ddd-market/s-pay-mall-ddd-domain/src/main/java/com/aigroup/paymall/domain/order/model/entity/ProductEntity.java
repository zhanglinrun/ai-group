package com.aigroup.paymall.domain.order.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductEntity {

    /** 商品ID */
    private String productId;
    /** 商品名称 */
    private String productName;
    /** 商品描述 */
    private String productDesc;
    /** 商品价格 */
    private BigDecimal price;
    /** Trusted package code from the server-side catalog. */
    private String productCode;
    /** Whole-credit entitlement snapshotted when the order is created. */
    private Long baseQuota;

}
