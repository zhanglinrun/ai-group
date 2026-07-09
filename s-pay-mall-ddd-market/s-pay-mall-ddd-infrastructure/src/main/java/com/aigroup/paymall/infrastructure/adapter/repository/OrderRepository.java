package com.aigroup.paymall.infrastructure.adapter.repository;

import com.aigroup.paymall.domain.order.adapter.event.PaySuccessMessageEvent;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import com.aigroup.paymall.infrastructure.event.EventPublisher;
import com.aigroup.paymall.types.event.BaseEvent;
import com.alibaba.fastjson.JSON;
import com.google.common.eventbus.EventBus;
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
    @Resource
    private PaySuccessMessageEvent paySuccessMessageEvent;
    @Resource
    private EventBus eventBus;
    @Resource
    private EventPublisher eventPublisher;

    @Override
    public void doSaveOrder(CreateOrderAggregate orderAggregate) {
        String userId = orderAggregate.getUserId();
        ProductEntity productEntity = orderAggregate.getProductEntity();
        OrderEntity orderEntity = orderAggregate.getOrderEntity();

        PayOrder order = new PayOrder();
        order.setUserId(userId);
        order.setProductId(productEntity.getProductId());
        order.setProductCode(orderEntity.getProductCode());
        order.setProductName(productEntity.getProductName());
        order.setOrderId(orderEntity.getOrderId());
        order.setOrderTime(orderEntity.getOrderTime());
        order.setTotalAmount(productEntity.getPrice());
        order.setStatus(orderEntity.getOrderStatusVO().getCode());
        order.setMarketType(MarketTypeVO.NO_MARKET.getCode());
        order.setMarketDeductionAmount(BigDecimal.ZERO);
        order.setPayAmount(productEntity.getPrice());
        order.setMarketType(orderEntity.getMarketType());

        orderDao.insert(order);
    }

    @Override
    public OrderEntity queryUnPayOrder(ShopCartEntity shopCartEntity) {
        // 1. 封装参数
        PayOrder orderReq = new PayOrder();
        orderReq.setUserId(shopCartEntity.getUserId());
        orderReq.setProductId(shopCartEntity.getProductId());
        orderReq.setProductCode(shopCartEntity.getProductCode());

        // 2. 查询到订�?
        PayOrder order = orderDao.queryUnPayOrder(orderReq);
        if (null == order) return null;

        // 3. 返回结果
        return OrderEntity.builder()
                .id(order.getId())
                .productId(order.getProductId())
                .productCode(order.getProductCode())
                .productName(order.getProductName())
                .orderId(order.getOrderId())
                .orderStatusVO(OrderStatusVO.valueOf(order.getStatus()))
                .orderTime(order.getOrderTime())
                .totalAmount(order.getTotalAmount())
                .payUrl(order.getPayUrl())
                .marketType(order.getMarketType())
                .marketDeductionAmount(order.getMarketDeductionAmount())
                .payAmount(order.getPayAmount())
                .build();
    }

    @Override
    public void updateOrderPayInfo(PayOrderEntity payOrderEntity) {
        PayOrder payOrderReq = PayOrder.builder()
                .userId(payOrderEntity.getUserId())
                .orderId(payOrderEntity.getOrderId())
                .status(payOrderEntity.getOrderStatus().getCode())
                .payUrl(payOrderEntity.getPayUrl())
                .marketType(payOrderEntity.getMarketType())
                .marketDeductionAmount(payOrderEntity.getMarketDeductionAmount())
                .payAmount(payOrderEntity.getPayAmount())
                .build();
        orderDao.updateOrderPayInfo(payOrderReq);
    }

    @Override
    public void changeOrderPaySuccess(String orderId, Date payTime) {
        PayOrder payOrderReq = new PayOrder();
        payOrderReq.setOrderId(orderId);
        payOrderReq.setStatus(OrderStatusVO.PAY_SUCCESS.getCode());
        payOrderReq.setPayTime(payTime);
        int updated = orderDao.changeOrderPaySuccess(payOrderReq);
        // Only publish when this callback actually transitioned the order (CREATE/PAY_WAIT -> PAY_SUCCESS);
        // a replayed/duplicate Alipay notify updates 0 rows and must not emit a fresh pay-success message.
        if (updated <= 0) {
            return;
        }

        // 不走拼团营销的直接结算发�?
        BaseEvent.EventMessage<PaySuccessMessageEvent.PaySuccessMessage> paySuccessMessageEventMessage = paySuccessMessageEvent.buildEventMessage(
                PaySuccessMessageEvent.PaySuccessMessage.builder()
                        .tradeNo(orderId)
                        .build());
        PaySuccessMessageEvent.PaySuccessMessage paySuccessMessage = paySuccessMessageEventMessage.getData();

        // 旧版发送消息方�?
        // eventBus.post(JSON.toJSONString(paySuccessMessage));

        eventPublisher.publish(paySuccessMessageEvent.topic(), JSON.toJSONString(paySuccessMessage));
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
    public void changeOrderMarketSettlement(List<String> outTradeNoList) {
        // 更新拼团结算状�?
        orderDao.changeOrderMarketSettlement(outTradeNoList);

        // 循环成功发送消�?- 一般在公司的场景里，还会有job任务扫描超时没有结算的订单，查询订单状态。查询对方服务端的接口，会被限制一次查询多少，频次多少�?
        outTradeNoList.forEach(outTradeNo -> {
            BaseEvent.EventMessage<PaySuccessMessageEvent.PaySuccessMessage> paySuccessMessageEventMessage = paySuccessMessageEvent.buildEventMessage(
                    PaySuccessMessageEvent.PaySuccessMessage.builder()
                            .tradeNo(outTradeNo)
                            .build());
            PaySuccessMessageEvent.PaySuccessMessage paySuccessMessage = paySuccessMessageEventMessage.getData();

            // 旧版发送消息方�?
            // eventBus.post(JSON.toJSONString(paySuccessMessage));

            eventPublisher.publish(paySuccessMessageEvent.topic(), JSON.toJSONString(paySuccessMessage));
        });
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
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .marketType(payOrder.getMarketType())
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
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .payTime(payOrder.getPayTime())
                .marketType(payOrder.getMarketType())
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
                .orderId(payOrder.getOrderId())
                .orderTime(payOrder.getOrderTime())
                .totalAmount(payOrder.getTotalAmount())
                .orderStatusVO(OrderStatusVO.valueOf(payOrder.getStatus()))
                .payUrl(payOrder.getPayUrl())
                .payTime(payOrder.getPayTime())
                .marketType(payOrder.getMarketType())
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

}
