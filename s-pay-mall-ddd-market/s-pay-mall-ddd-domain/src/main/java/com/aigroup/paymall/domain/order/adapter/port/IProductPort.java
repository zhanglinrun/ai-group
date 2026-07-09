package com.aigroup.paymall.domain.order.adapter.port;

import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;

import java.math.BigDecimal;
import java.util.Date;

public interface IProductPort {
    ProductEntity queryProductByProductId(String productId);

    MarketPayDiscountEntity lockMarketPayOrder(String userId, String teamId, Long activityId, String productId, String orderId);

    /**
     * 通知拼团系统结算（登记该成员已支付）。
     * 返回 group 是否确认登记成功；成功后调用方置结算确认位，补偿任务不再重扫（区分"未结算"与"未成团"）。
     */
    boolean settlementMarketPayOrder(String userId, String orderId, Date orderTime);

    /**
     * 通知拼团系统退单（释放组队库存）。
     * 返回通知是否成功；不再吞异常，调用方据此如实反馈或交补偿任务重试。
     */
    boolean refundMarketPayOrder(String userId, String orderId);

}
