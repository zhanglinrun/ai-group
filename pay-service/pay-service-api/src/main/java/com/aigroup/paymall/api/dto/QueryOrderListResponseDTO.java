package com.aigroup.paymall.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class QueryOrderListResponseDTO {

    /** 订单列表 */
    private List<OrderInfo> orderList;
    /** 是否还有更多数据 */
    private Boolean hasMore;
    /** 最后一条记录的ID */
    private Long lastId;

    @Data
    public static class OrderInfo {
        /** 订单ID */
        private Long id;
        /** 用户ID */
        private String userId;
        /** 商品ID */
        private String productId;
        /** 商品名称 */
        private String productName;
        /** 订单号 */
        private String orderId;
        /** 下单时间 */
        private Date orderTime;
        /** 订单金额 */
        private BigDecimal totalAmount;
        /** 订单状态 */
        private String status;
        /** 支付链接 */
        private String payUrl;
        /** 营销类型 */
        private Integer marketType;
        /** 营销优惠金额 */
        private BigDecimal marketDeductionAmount;
        /** 实际支付金额 */
        private BigDecimal payAmount;
        /** 支付时间 */
        private Date payTime;
    }

}