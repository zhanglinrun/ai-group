package com.aigroup.paymall.domain.order.service;

import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.alipay.api.AlipayApiException;

import java.util.Date;
import java.util.List;

public interface IOrderService {

    PayOrderEntity createOrder(ShopCartEntity shopCartEntity) throws Exception;

    OrderEntity queryOrderByOrderId(String orderId);

    void changeOrderPaySuccess(String orderId, Date orderTime);

    List<String> queryNoPayNotifyOrder();

    List<String> queryTimeoutCloseOrderList();

    boolean changeOrderClose(String orderId);

    /**
     * C2: re-send group settlement for group-buy orders stuck in PAY_SUCCESS
     * (settlement HTTP call was lost); group settlement is idempotent.
     *
     * @return number of orders a settlement retry was sent for
     */
    int compensateMarketSettlement();

    void changeOrderMarketSettlement(List<String> outTradeNoList);

    List<OrderEntity> queryUserOrderList(String userId, Long lastId, Integer pageSize);

    /**
     * 营销退单
     */
    boolean refundMarketOrder(String userId, String orderId);

    /**
     * 接收拼团退单消息
     */
    boolean refundPayOrder(String userId, String orderId) throws AlipayApiException;

}
