package com.aigroup.paymall.domain.order.adapter.port;

import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;

import java.math.BigDecimal;
import java.util.Date;

public interface IProductPort {
    ProductEntity queryProductByProductId(String productId);

    MarketPayDiscountEntity lockMarketPayOrder(String userId, String teamId, Long activityId, String productId, String orderId);

    void settlementMarketPayOrder(String userId, String orderId, Date orderTime);

    void refundMarketPayOrder(String userId, String orderId);

}
