package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderCreateStage;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.adapter.repository.OrderRepository;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrderRepositoryIdempotencyTest {

    @Test
    public void duplicateClientKeyReturnsCommittedWinner() {
        IOrderDao orderDao = mock(IOrderDao.class);
        OrderRepository repository = repository(orderDao);
        CreateOrderAggregate aggregate = aggregate();
        doThrow(new DuplicateKeyException("uq_user_client_request"))
                .when(orderDao).insert(any(PayOrder.class));
        when(orderDao.queryOrderByClientRequestId("u1", "request-1"))
                .thenReturn(winner());

        OrderEntity existing = repository.saveOrderIfAbsent(aggregate);

        Assert.assertEquals("winner-order", existing.getOrderId());
        Assert.assertEquals("fingerprint-1", existing.getRequestFingerprint());
        verify(orderDao).queryOrderByClientRequestId("u1", "request-1");
    }

    @Test
    public void unrelatedUniqueConflictIsNotMisreportedAsIdempotentReplay() {
        IOrderDao orderDao = mock(IOrderDao.class);
        OrderRepository repository = repository(orderDao);
        doThrow(new DuplicateKeyException("uq_order_id"))
                .when(orderDao).insert(any(PayOrder.class));
        when(orderDao.queryOrderByClientRequestId("u1", "request-1")).thenReturn(null);

        Assert.assertThrows(DuplicateKeyException.class,
                () -> repository.saveOrderIfAbsent(aggregate()));
    }

    private OrderRepository repository(IOrderDao orderDao) {
        OrderRepository repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "orderDao", orderDao);
        return repository;
    }

    private CreateOrderAggregate aggregate() {
        ProductEntity product = ProductEntity.builder()
                .productId("P100")
                .productCode("QUOTA_LIGHT")
                .productName("轻量额度包")
                .price(new BigDecimal("12.00"))
                .baseQuota(60L)
                .build();
        OrderEntity order = OrderEntity.builder()
                .clientRequestId("request-1")
                .requestFingerprint("fingerprint-1")
                .createStage(OrderCreateStage.LOCAL_CREATED)
                .createOwnerToken("owner-1")
                .productId("P100")
                .productCode("QUOTA_LIGHT")
                .productName("轻量额度包")
                .baseQuotaSnapshot(60L)
                .orderId("candidate-order")
                .orderTime(new Date())
                .orderStatusVO(OrderStatusVO.CREATE)
                .marketType(MarketTypeVO.NO_MARKET.getCode())
                .build();
        return CreateOrderAggregate.builder()
                .userId("u1")
                .productEntity(product)
                .orderEntity(order)
                .build();
    }

    private PayOrder winner() {
        return PayOrder.builder()
                .id(1L)
                .clientRequestId("request-1")
                .requestFingerprint("fingerprint-1")
                .createStage(OrderCreateStage.PREPAY_READY.name())
                .userId("u1")
                .productId("P100")
                .productCode("QUOTA_LIGHT")
                .productName("轻量额度包")
                .baseQuotaSnapshot(60L)
                .orderId("winner-order")
                .orderTime(new Date())
                .totalAmount(new BigDecimal("12.00"))
                .status(OrderStatusVO.PAY_WAIT.getCode())
                .marketType(MarketTypeVO.NO_MARKET.getCode())
                .marketDeductionAmount(BigDecimal.ZERO)
                .payAmount(new BigDecimal("12.00"))
                .build();
    }
}
