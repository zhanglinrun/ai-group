package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.infrastructure.adapter.repository.OrderRepository;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class OrderRepositorySnapshotTest {

    @Test
    public void savedOrderKeepsPriceAndBaseQuotaAfterCatalogObjectChanges() {
        IOrderDao orderDao = mock(IOrderDao.class);
        OrderRepository repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "orderDao", orderDao);

        ProductEntity product = ProductEntity.builder()
                .productId("9890002")
                .productCode("QUOTA_LIGHT")
                .productName("轻量额度包")
                .price(new BigDecimal("12.00"))
                .baseQuota(60L)
                .build();
        OrderEntity order = OrderEntity.builder()
                .productId(product.getProductId())
                .productCode(product.getProductCode())
                .productName(product.getProductName())
                .baseQuotaSnapshot(product.getBaseQuota())
                .orderId("order-1")
                .orderTime(new Date())
                .orderStatusVO(OrderStatusVO.CREATE)
                .marketType(MarketTypeVO.NO_MARKET.getCode())
                .build();

        repository.doSaveOrder(CreateOrderAggregate.builder()
                .userId("10001").productEntity(product).orderEntity(order).build());

        product.setPrice(new BigDecimal("99.00"));
        product.setBaseQuota(999L);
        ArgumentCaptor<PayOrder> saved = ArgumentCaptor.forClass(PayOrder.class);
        verify(orderDao).insert(saved.capture());
        assertEquals(0, new BigDecimal("12.00").compareTo(saved.getValue().getTotalAmount()));
        assertEquals(Long.valueOf(60L), saved.getValue().getBaseQuotaSnapshot());
    }
}
