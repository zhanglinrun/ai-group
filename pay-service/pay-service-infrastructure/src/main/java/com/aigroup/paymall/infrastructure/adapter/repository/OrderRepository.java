package com.aigroup.paymall.infrastructure.adapter.repository;

import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderCreateStage;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class OrderRepository implements IOrderRepository {

    @Resource
    private IOrderDao orderDao;
    @Override
    public OrderEntity saveOrderIfAbsent(CreateOrderAggregate orderAggregate) {
        String userId = orderAggregate.getUserId();
        ProductEntity productEntity = orderAggregate.getProductEntity();
        OrderEntity orderEntity = orderAggregate.getOrderEntity();

        PayOrder order = new PayOrder();
        order.setClientRequestId(orderEntity.getClientRequestId());
        order.setRequestFingerprint(orderEntity.getRequestFingerprint());
        order.setCreateStage(orderEntity.getCreateStage().name());
        order.setCreateOwnerToken(orderEntity.getCreateOwnerToken());
        order.setUserId(userId);
        order.setProductId(productEntity.getProductId());
        order.setProductCode(orderEntity.getProductCode());
        order.setProductName(productEntity.getProductName());
        order.setBaseQuotaSnapshot(orderEntity.getBaseQuotaSnapshot());
        order.setOrderId(orderEntity.getOrderId());
        order.setOrderTime(orderEntity.getOrderTime());
        order.setTotalAmount(productEntity.getPrice());
        order.setStatus(orderEntity.getOrderStatusVO().getCode());
        order.setMarketType(MarketTypeVO.NO_MARKET.getCode());
        order.setMarketDeductionAmount(BigDecimal.ZERO);
        order.setPayAmount(productEntity.getPrice());
        order.setMarketType(orderEntity.getMarketType());
        order.setGroupActivityId(orderEntity.getGroupActivityId());
        order.setGroupTeamId(orderEntity.getGroupTeamId());

        try {
            orderDao.insert(order);
            return null;
        } catch (DuplicateKeyException duplicateKeyException) {
            PayOrder existing = orderDao.queryOrderByClientRequestId(userId, orderEntity.getClientRequestId());
            if (existing == null) {
                throw duplicateKeyException;
            }
            return toOrderEntity(existing);
        }
    }

    @Override
    public OrderEntity queryOrderByClientRequestId(String userId, String clientRequestId) {
        PayOrder payOrder = orderDao.queryOrderByClientRequestId(userId, clientRequestId);
        return payOrder == null ? null : toOrderEntity(payOrder);
    }

    @Override
    public boolean claimOrderCreation(String orderId, String ownerToken) {
        return orderDao.claimOrderCreation(orderId, ownerToken) == 1;
    }

    @Override
    public void releaseOrderCreationClaim(String orderId, String ownerToken) {
        orderDao.releaseOrderCreationClaim(orderId, ownerToken);
    }

    @Override
    public boolean markGroupLocked(String orderId, String ownerToken, Integer marketType,
                                   BigDecimal marketDeductionAmount, BigDecimal payAmount) {
        PayOrder payOrder = PayOrder.builder()
                .orderId(orderId)
                .createOwnerToken(ownerToken)
                .marketType(marketType)
                .marketDeductionAmount(marketDeductionAmount)
                .payAmount(payAmount)
                .build();
        return orderDao.markGroupLocked(payOrder) == 1;
    }

    @Override
    public boolean markProviderStarted(String orderId, String ownerToken) {
        return orderDao.markProviderStarted(orderId, ownerToken) == 1;
    }

    @Override
    public boolean completeOrderPrepay(PayOrderEntity payOrderEntity, String ownerToken) {
        PayOrder payOrderReq = PayOrder.builder()
                .userId(payOrderEntity.getUserId())
                .orderId(payOrderEntity.getOrderId())
                .createOwnerToken(ownerToken)
                .status(payOrderEntity.getOrderStatus().getCode())
                .payUrl(payOrderEntity.getPayUrl())
                .marketType(payOrderEntity.getMarketType())
                .marketDeductionAmount(payOrderEntity.getMarketDeductionAmount())
                .payAmount(payOrderEntity.getPayAmount())
                .build();
        return orderDao.completeOrderPrepay(payOrderReq) == 1;
    }

    @Override
    public void markOrderCreationManualReview(String orderId, String ownerToken) {
        orderDao.markOrderCreationManualReview(orderId, ownerToken);
    }

    @Override
    public void updateOrderPayUrl(String orderId, String payUrl) {
        orderDao.updateOrderPayUrl(orderId, payUrl);
    }

    @Override
    public void changeOrderDealDone(String orderId) {
        orderDao.changeOrderDealDone(orderId);
    }

    @Override
    public void changeOrderPaySuccess(String orderId, Date payTime) {
        PayOrder payOrderReq = new PayOrder();
        payOrderReq.setOrderId(orderId);
        payOrderReq.setStatus(OrderStatusVO.PAY_SUCCESS.getCode());
        payOrderReq.setPayTime(payTime);
        orderDao.changeOrderPaySuccess(payOrderReq);
    }

    @Override
    public void changeMarketOrderPaySuccess(String orderId) {
        PayOrder payOrderReq = new PayOrder();
        payOrderReq.setOrderId(orderId);
        payOrderReq.setStatus(OrderStatusVO.PAY_SUCCESS.getCode());
        orderDao.changeOrderPaySuccess(payOrderReq);
    }

    @Override
    public List<String> queryNoPayNotifyOrder() {
        return orderDao.queryNoPayNotifyOrder();
    }

    @Override
    public List<String> queryTimeoutCloseOrderList() {
        return orderDao.queryTimeoutCloseOrderList();
    }

    @Override
    public List<OrderEntity> queryPaySuccessMarketTimeoutOrders() {
        List<PayOrder> payOrders = orderDao.queryPaySuccessMarketTimeoutOrderList();
        if (null == payOrders || payOrders.isEmpty()) {
            return new ArrayList<>();
        }
        return payOrders.stream().map(payOrder -> OrderEntity.builder()
                .userId(payOrder.getUserId())
                .orderId(payOrder.getOrderId())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .marketType(payOrder.getMarketType())
                .payAmount(payOrder.getPayAmount())
                .payTime(payOrder.getPayTime())
                .build()).collect(Collectors.toList());
    }

    @Override
    public List<OrderEntity> queryWaitRefundTimeoutOrders() {
        List<PayOrder> payOrders = orderDao.queryWaitRefundTimeoutOrderList();
        if (null == payOrders || payOrders.isEmpty()) {
            return new ArrayList<>();
        }
        return payOrders.stream().map(payOrder -> OrderEntity.builder()
                .userId(payOrder.getUserId())
                .orderId(payOrder.getOrderId())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .marketType(payOrder.getMarketType())
                .payAmount(payOrder.getPayAmount())
                .payTime(payOrder.getPayTime())
                .build()).collect(Collectors.toList());
    }

    @Override
    public void markSettlementNotified(String orderId) {
        orderDao.markSettlementNotified(orderId);
    }

    @Override
    public boolean changeOrderClose(String orderId) {
        return orderDao.changeOrderClose(orderId);
    }

    @Override
    public List<String> changeOrderMarketSettlement(List<String> outTradeNoList) {
        if (null == outTradeNoList || outTradeNoList.isEmpty()) {
            return new ArrayList<>();
        }

        // 只把 PAY_SUCCESS 迁移为 MARKET；回查真正迁移成功(现为 MARKET)的订单。
        // 未支付(PAY_WAIT)/已关闭(CLOSE)订单不在其中，避免给未支付订单写权益 outbox。
        orderDao.changeOrderMarketSettlement(outTradeNoList);
        List<String> settledOrderIds = orderDao.queryMarketSettledOrderIds(outTradeNoList);
        if (null == settledOrderIds || settledOrderIds.isEmpty()) {
            return new ArrayList<>();
        }

        return settledOrderIds;
    }

    @Override
    public OrderEntity queryOrderByOrderId(String orderId) {
        PayOrder payOrder = orderDao.queryOrderByOrderId(orderId);
        if (null == payOrder) return null;

        return OrderEntity.builder()
                .id(payOrder.getId())
                .userId(payOrder.getUserId())
                .productId(payOrder.getProductId())
                .productCode(payOrder.getProductCode())
                .productName(payOrder.getProductName())
                .baseQuotaSnapshot(payOrder.getBaseQuotaSnapshot())
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .payTime(payOrder.getPayTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupTeamId(payOrder.getGroupTeamId())
                .marketDeductionAmount(payOrder.getMarketDeductionAmount())
                .payAmount(payOrder.getPayAmount())
                .build();
    }

    @Override
    public List<OrderEntity> queryUserOrderList(String userId, Long lastId, Integer pageSize) {
        List<PayOrder> payOrderList = orderDao.queryUserOrderList(userId, lastId, pageSize);
        if (null == payOrderList || payOrderList.isEmpty()) {
            return new ArrayList<>();
        }

        return payOrderList.stream().map(payOrder -> OrderEntity.builder()
                .id(payOrder.getId())
                .userId(payOrder.getUserId())
                .productId(payOrder.getProductId())
                .productCode(payOrder.getProductCode())
                .productName(payOrder.getProductName())
                .baseQuotaSnapshot(payOrder.getBaseQuotaSnapshot())
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .payTime(payOrder.getPayTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupTeamId(payOrder.getGroupTeamId())
                .marketDeductionAmount(payOrder.getMarketDeductionAmount())
                .payAmount(payOrder.getPayAmount())
                .build()).collect(Collectors.toList());
    }

    @Override
    public OrderEntity queryOrderByUserIdAndOrderId(String userId, String orderId) {
        PayOrder payOrder = orderDao.queryOrderByUserIdAndOrderId(userId, orderId);
        if (null == payOrder) return null;

        return OrderEntity.builder()
                .id(payOrder.getId())
                .userId(payOrder.getUserId())
                .productId(payOrder.getProductId())
                .productCode(payOrder.getProductCode())
                .productName(payOrder.getProductName())
                .baseQuotaSnapshot(payOrder.getBaseQuotaSnapshot())
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .payTime(payOrder.getPayTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupTeamId(payOrder.getGroupTeamId())
                .marketDeductionAmount(payOrder.getMarketDeductionAmount())
                .payAmount(payOrder.getPayAmount())
                .build();
    }

    @Override
    public boolean refundOrder(String userId, String orderId) {
        return orderDao.refundOrder(userId, orderId);
    }

    @Override
    public boolean refundMarketOrder(String userId, String orderId) {
        return orderDao.refundMarketOrder(userId, orderId);
    }

    private OrderEntity toOrderEntity(PayOrder payOrder) {
        return OrderEntity.builder()
                .id(payOrder.getId())
                .clientRequestId(payOrder.getClientRequestId())
                .requestFingerprint(payOrder.getRequestFingerprint())
                .createStage(payOrder.getCreateStage() == null ? null : OrderCreateStage.valueOf(payOrder.getCreateStage()))
                .createOwnerToken(payOrder.getCreateOwnerToken())
                .createLeaseUntil(payOrder.getCreateLeaseUntil())
                .userId(payOrder.getUserId())
                .productId(payOrder.getProductId())
                .productCode(payOrder.getProductCode())
                .productName(payOrder.getProductName())
                .baseQuotaSnapshot(payOrder.getBaseQuotaSnapshot())
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(payOrder.getStatus() == null ? null : OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .payTime(payOrder.getPayTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupTeamId(payOrder.getGroupTeamId())
                .marketDeductionAmount(payOrder.getMarketDeductionAmount())
                .payAmount(payOrder.getPayAmount())
                .build();
    }

}
