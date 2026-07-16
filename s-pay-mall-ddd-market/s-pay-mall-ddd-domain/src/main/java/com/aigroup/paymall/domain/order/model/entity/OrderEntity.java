package com.aigroup.paymall.domain.order.model.entity;

import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderEntity {

    // 主键ID
    private Long id;
    // 用户ID
    private String userId;
    private String productId;
    private String productCode;
    private String productName;
    /** Whole-credit package entitlement captured at order creation. */
    private Long baseQuotaSnapshot;
    private String orderId;
    private Date orderTime;
    private BigDecimal totalAmount;
    private OrderStatusVO orderStatusVO;
    private String payUrl;
    // 营销类型；0无营销、1拼团营销
    private Integer marketType;
    // 营销金额；优惠金额
    private BigDecimal marketDeductionAmount;
    // 支付金额
    private BigDecimal payAmount;
    // 支付时间
    private Date payTime;

}
