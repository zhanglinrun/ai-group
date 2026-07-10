package com.aigroup.paymall.domain.order.adapter.repository;

import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;

import java.util.Date;
import java.util.List;

public interface IOrderRepository {
    void doSaveOrder(CreateOrderAggregate orderAggregate);

    OrderEntity queryUnPayOrder(ShopCartEntity shopCartEntity);

    void updateOrderPayInfo(PayOrderEntity payOrderEntity);

    void changeOrderPaySuccess(String orderId, Date payTime);

    void changeMarketOrderPaySuccess(String orderId);

    List<String> queryNoPayNotifyOrder();

    List<String> queryTimeoutCloseOrderList();

    List<OrderEntity> queryPaySuccessMarketTimeoutOrders();

    List<OrderEntity> queryWaitRefundTimeoutOrders();

    void markSettlementNotified(String orderId);

    boolean changeOrderClose(String orderId);

    /**
     * 将成团回调中实际处于 PAY_SUCCESS 的订单迁移为 MARKET，并只对迁移成功的订单发送履约消息。
     *
     * @return 真正结算成功（现为 MARKET）的订单号；未支付/已关闭订单不在其中，调用方据此发放权益
     */
    List<String> changeOrderMarketSettlement(List<String> outTradeNoList);

    OrderEntity queryOrderByOrderId(String orderId);

    List<OrderEntity> queryUserOrderList(String userId, Long lastId, Integer pageSize);

    OrderEntity queryOrderByUserIdAndOrderId(String userId, String orderId);

    boolean refundOrder(String userId, String orderId);

    boolean refundMarketOrder(String userId, String orderId);

}
