package com.aigroup.paymall.domain.order.adapter.repository;

import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;

import java.util.Date;
import java.util.List;
import java.math.BigDecimal;

public interface IOrderRepository {
    /**
     * 插入成功返回 {@code null}；若同一 userId + clientRequestId 已由并发请求插入，返回赢家订单。
     * 其他唯一键冲突必须继续抛出，不能误判为幂等命中。
     */
    OrderEntity saveOrderIfAbsent(CreateOrderAggregate orderAggregate);

    OrderEntity queryOrderByClientRequestId(String userId, String clientRequestId);

    boolean claimOrderCreation(String orderId, String ownerToken);

    void releaseOrderCreationClaim(String orderId, String ownerToken);

    boolean markGroupLocked(String orderId, String ownerToken, Integer marketType,
                            BigDecimal marketDeductionAmount, BigDecimal payAmount);

    boolean markProviderStarted(String orderId, String ownerToken);

    boolean completeOrderPrepay(PayOrderEntity payOrderEntity, String ownerToken);

    void markOrderCreationManualReview(String orderId, String ownerToken);

    void updateOrderPayUrl(String orderId, String payUrl);

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
