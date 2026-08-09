package com.aigroup.paymall.domain.order.model.entity;

import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderCreateStage;
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
    // 客户端购买请求号，同一用户内唯一
    private String clientRequestId;
    // 规范化下单载荷的 SHA-256 指纹
    private String requestFingerprint;
    // durable 创建状态与当前 owner 租约
    private OrderCreateStage createStage;
    private String createOwnerToken;
    private Date createLeaseUntil;
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
    // 下单时固化的拼团活动与队伍，直购均为空
    private Long groupActivityId;
    private String groupTeamId;
    // 营销金额；优惠金额
    private BigDecimal marketDeductionAmount;
    // 支付金额
    private BigDecimal payAmount;
    // 支付时间
    private Date payTime;

}
