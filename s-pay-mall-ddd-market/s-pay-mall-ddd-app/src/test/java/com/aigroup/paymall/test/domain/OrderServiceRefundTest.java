package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.OrderService;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paid quota orders cannot be refunded from the user-facing API after quota may
 * have been granted. Internal failed-team refunds still use refundPayOrder.
 */
public class OrderServiceRefundTest {

    private OrderService orderService;
    private IOrderRepository repository;
    private IProductPort port;
    private AlipayClient alipayClient;
    private IBenefitEventService benefitEventService;

    @Before
    public void setUp() {
        repository = mock(IOrderRepository.class);
        port = mock(IProductPort.class);
        alipayClient = mock(AlipayClient.class);
        benefitEventService = mock(IBenefitEventService.class);
        orderService = new OrderService(repository, port);
        ReflectionTestUtils.setField(orderService, "alipayClient", alipayClient);
        ReflectionTestUtils.setField(orderService, "benefitEventService", benefitEventService);
        // real template + mocked manager so the local-update lambda actually runs
        ReflectionTestUtils.setField(orderService, "transactionTemplate",
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private OrderEntity order(String userId, String orderId, OrderStatusVO status, MarketTypeVO marketType) {
        return OrderEntity.builder()
                .userId(userId)
                .orderId(orderId)
                .orderStatusVO(status)
                .marketType(marketType.getCode())
                .payAmount(new BigDecimal("19.99"))
                .build();
    }

    @Test
    public void refundMarketOrder_paidNoMarketOrder_rejectsSelfServiceRefund() throws Exception {
        when(repository.queryOrderByUserIdAndOrderId("u1", "order-001"))
                .thenReturn(order("u1", "order-001", OrderStatusVO.PAY_SUCCESS, MarketTypeVO.NO_MARKET));
        boolean result = orderService.refundMarketOrder("u1", "order-001");

        Assert.assertFalse(result);
        verify(alipayClient, never()).execute(any(AlipayTradeRefundRequest.class));
        verify(repository, never()).refundOrder("u1", "order-001");
        verify(repository, never()).refundMarketOrder("u1", "order-001");
        verify(port, never()).refundMarketPayOrder("u1", "order-001");
    }

    @Test
    public void refundMarketOrder_paidGroupBuyOrder_rejectsSelfServiceRefund() throws Exception {
        when(repository.queryOrderByUserIdAndOrderId("u1", "order-002"))
                .thenReturn(order("u1", "order-002", OrderStatusVO.PAY_SUCCESS, MarketTypeVO.GROUP_BUY_MARKET));
        boolean result = orderService.refundMarketOrder("u1", "order-002");

        Assert.assertFalse(result);
        verify(port, never()).refundMarketPayOrder("u1", "order-002");
        verify(repository, never()).refundMarketOrder("u1", "order-002");
        verify(alipayClient, never()).execute(any(AlipayTradeRefundRequest.class));
    }

    @Test
    public void refundMarketOrder_unpaidNoMarketOrder_closesLocallyWithoutAlipay() throws Exception {
        when(repository.queryOrderByUserIdAndOrderId("u1", "order-003"))
                .thenReturn(order("u1", "order-003", OrderStatusVO.PAY_WAIT, MarketTypeVO.NO_MARKET));
        when(repository.refundOrder("u1", "order-003")).thenReturn(true);

        boolean result = orderService.refundMarketOrder("u1", "order-003");

        Assert.assertTrue(result);
        verify(repository).refundOrder("u1", "order-003");
        verify(alipayClient, never()).execute(any(AlipayTradeRefundRequest.class));
        verify(port, never()).refundMarketPayOrder("u1", "order-003");
    }

    @Test
    public void refundMarketOrder_closedOrder_isRejected() {
        when(repository.queryOrderByUserIdAndOrderId("u1", "order-004"))
                .thenReturn(order("u1", "order-004", OrderStatusVO.CLOSE, MarketTypeVO.NO_MARKET));

        Assert.assertFalse(orderService.refundMarketOrder("u1", "order-004"));
    }

    @Test
    public void refundPayOrder_alreadyClosed_reportsSuccessWithoutAlipayCall() throws Exception {
        when(repository.queryOrderByUserIdAndOrderId("u1", "order-005"))
                .thenReturn(order("u1", "order-005", OrderStatusVO.CLOSE, MarketTypeVO.GROUP_BUY_MARKET));

        // redelivered team_refund message must be acked, not dead-lettered
        Assert.assertTrue(orderService.refundPayOrder("u1", "order-005"));
        verify(alipayClient, never()).execute(any(AlipayTradeRefundRequest.class));
    }

    @Test
    public void refundPayOrder_alipayBusinessFailure_returnsFalse() throws Exception {
        when(repository.queryOrderByUserIdAndOrderId("u1", "order-006"))
                .thenReturn(order("u1", "order-006", OrderStatusVO.WAIT_REFUND, MarketTypeVO.GROUP_BUY_MARKET));
        AlipayTradeRefundResponse response = mock(AlipayTradeRefundResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(alipayClient.execute(any(AlipayTradeRefundRequest.class))).thenReturn(response);

        Assert.assertFalse(orderService.refundPayOrder("u1", "order-006"));
        // no local close when the alipay refund was rejected
        verify(repository, never()).refundOrder("u1", "order-006");
    }

}
