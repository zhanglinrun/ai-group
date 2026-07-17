package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.OrderService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrderServiceDirectOutboxTest {

    private IOrderRepository repository;
    private IBenefitEventService benefitEventService;
    private PlatformTransactionManager transactionManager;
    private OrderService orderService;
    private TransactionStatus transactionStatus;

    @Before
    public void setUp() {
        repository = mock(IOrderRepository.class);
        benefitEventService = mock(IBenefitEventService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);

        orderService = new OrderService(repository, mock(IProductPort.class));
        ReflectionTestUtils.setField(orderService, "benefitEventService", benefitEventService);
        ReflectionTestUtils.setField(orderService, "transactionTemplate",
                new TransactionTemplate(transactionManager));
    }

    @Test
    public void directPaymentCommitsOrderAndBothOutboxRowsInOneTransaction() {
        OrderEntity before = order("order-direct", OrderStatusVO.PAY_WAIT);
        OrderEntity after = order("order-direct", OrderStatusVO.PAY_SUCCESS);
        when(repository.queryOrderByOrderId("order-direct")).thenReturn(before, after);
        Date payTime = new Date();

        orderService.changeOrderPaySuccess("order-direct", payTime);

        verify(repository).changeOrderPaySuccess("order-direct", payTime);
        verify(benefitEventService).enqueueCompletedOrderEvents(
                Collections.singletonList("order-direct"), null);
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, never()).rollback(any(TransactionStatus.class));
    }

    @Test
    public void closedDirectOrderDoesNotCreateOutboxRows() {
        OrderEntity closed = order("order-closed", OrderStatusVO.CLOSE);
        when(repository.queryOrderByOrderId("order-closed")).thenReturn(closed, closed);

        orderService.changeOrderPaySuccess("order-closed", new Date());

        verify(benefitEventService, never()).enqueueCompletedOrderEvents(any(), any());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    public void outboxInsertFailureRollsBackDirectPaymentTransaction() {
        OrderEntity before = order("order-rollback", OrderStatusVO.PAY_WAIT);
        OrderEntity after = order("order-rollback", OrderStatusVO.PAY_SUCCESS);
        when(repository.queryOrderByOrderId("order-rollback")).thenReturn(before, after);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(benefitEventService).enqueueCompletedOrderEvents(any(), any());

        assertThrows(IllegalStateException.class,
                () -> orderService.changeOrderPaySuccess("order-rollback", new Date()));

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
    }

    private OrderEntity order(String orderId, OrderStatusVO status) {
        return OrderEntity.builder()
                .userId("10001")
                .orderId(orderId)
                .orderStatusVO(status)
                .marketType(MarketTypeVO.NO_MARKET.getCode())
                .build();
    }
}
