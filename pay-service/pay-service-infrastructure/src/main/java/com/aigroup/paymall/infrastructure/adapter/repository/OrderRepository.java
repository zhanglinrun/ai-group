package com.aigroup.paymall.infrastructure.adapter.repository;

import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.TeamSettlementMember;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderCreateStage;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
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
        order.setGroupSource(orderEntity.getGroupSource());
        order.setGroupChannel(orderEntity.getGroupChannel());

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
                                   BigDecimal marketDeductionAmount, BigDecimal payAmount,
                                   String groupTeamId, String groupSource, String groupChannel) {
        PayOrder payOrder = PayOrder.builder()
                .orderId(orderId)
                .createOwnerToken(ownerToken)
                .marketType(marketType)
                .marketDeductionAmount(marketDeductionAmount)
                .payAmount(payAmount)
                .groupTeamId(groupTeamId)
                .groupSource(groupSource)
                .groupChannel(groupChannel)
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
    public List<String> changeOrderMarketSettlement(String teamId, Long activityId, List<TeamSettlementMember> members) {
        if (teamId == null || teamId.isBlank() || activityId == null || activityId <= 0
                || members == null || members.isEmpty()) {
            throw new IllegalArgumentException("formed team identity is required");
        }
        List<String> settledOrderIds = new ArrayList<>();
        for (TeamSettlementMember member : members) {
            if (member == null || member.userId() == null || member.userId().isBlank()
                    || member.source() == null || member.source().isBlank()
                    || member.channel() == null || member.channel().isBlank()
                    || member.outTradeNo() == null || member.outTradeNo().isBlank()) {
                throw new IllegalArgumentException("formed team member identity is required");
            }
            PayOrder order = PayOrder.builder().orderId(member.outTradeNo()).userId(member.userId())
                    .groupTeamId(teamId).groupActivityId(activityId)
                    .groupSource(member.source()).groupChannel(member.channel()).build();
            if (orderDao.changeOrderMarketSettlement(order) == 1) {
                settledOrderIds.add(member.outTradeNo());
                continue;
            }
            PayOrder existing = orderDao.queryOrderByOrderId(member.outTradeNo());
            if (existing == null) {
                log.info("skip formed team member without local Pay order orderId:{}", member.outTradeNo());
                continue;
            }
            if (!Objects.equals(existing.getUserId(), member.userId())
                    || !Objects.equals(existing.getGroupTeamId(), teamId)
                    || !Objects.equals(existing.getGroupActivityId(), activityId)
                    || !Objects.equals(existing.getGroupSource(), member.source())
                    || !Objects.equals(existing.getGroupChannel(), member.channel())
                    || !Objects.equals(existing.getMarketType(), MarketTypeVO.GROUP_BUY_MARKET.getCode())) {
                throw new IllegalStateException("formed team notification ownership mismatch: " + member.outTradeNo());
            }
            if (!OrderStatusVO.MARKET.getCode().equals(existing.getStatus())
                    && !OrderStatusVO.WAIT_REFUND.getCode().equals(existing.getStatus())
                    && !(OrderStatusVO.CLOSE.getCode().equals(existing.getStatus())
                    && existing.getPayTime() != null)) {
                throw new IllegalStateException("formed team notification order not settled: " + member.outTradeNo());
            }
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
                .updateTime(payOrder.getUpdateTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupSource(payOrder.getGroupSource())
                .groupChannel(payOrder.getGroupChannel())
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
                .updateTime(payOrder.getUpdateTime())
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
                .updateTime(payOrder.getUpdateTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupSource(payOrder.getGroupSource())
                .groupChannel(payOrder.getGroupChannel())
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
                .updateTime(payOrder.getUpdateTime())
                .marketType(payOrder.getMarketType())
                .groupActivityId(payOrder.getGroupActivityId())
                .groupTeamId(payOrder.getGroupTeamId())
                .groupSource(payOrder.getGroupSource())
                .groupChannel(payOrder.getGroupChannel())
                .marketDeductionAmount(payOrder.getMarketDeductionAmount())
                .payAmount(payOrder.getPayAmount())
                .build();
    }

}
