package com.aigroup.paymall.infrastructure.dao.po;

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
public class PayOrder {

    // 自增ID
    private Long id;
    // 客户端购买请求号，同一用户内唯一
    private String clientRequestId;
    // 规范化请求载荷 SHA-256
    private String requestFingerprint;
    // durable 创建状态与当前 owner 租约
    private String createStage;
    private String createOwnerToken;
    private Date createLeaseUntil;
    // 用户ID
    private String userId;
    // 商品ID
    private String productId;
    // member SKU 编码
    private String productCode;
    // 商品名称
    private String productName;
    /** 下单时基础额度快照（整额度点） */
    private Long baseQuotaSnapshot;
    // 订单ID
    private String orderId;
    // 下单时间
    private Date orderTime;
    // 订单金额
    private BigDecimal totalAmount;
    // 订单状态；create-创建完成、pay_wait-等待支付、pay_success-支付成功、deal_done-交易完成、close-订单关单
    private String status;
    // 支付信息
    private String payUrl;
    // 支付时间
    private Date payTime;
    // 营销类型；0无营销、1拼团营销
    private Integer marketType;
    // 下单时固化的拼团活动与队伍
    private Long groupActivityId;
    private String groupTeamId;
    // 营销金额；优惠金额
    private BigDecimal marketDeductionAmount;
    // 支付金额
    private BigDecimal payAmount;
    // 拼团结算是否已通知成功；1=group已确认登记
    private Integer settlementNotified;
    // 创建时间
    private Date createTime;
    // 更新时间
    private Date updateTime;

}
