package com.aigroup.paymall.test.domain;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.port.MarketSettlementResult;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.OrderService;
import org.junit.Test;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OrderServiceTerminalSettlementTest {

    @Test
    public void paidOrderIsRefundedWhenGroupDeterministicallyRejectsSettlement() throws Exception {
        IOrderRepository repository = mock(IOrderRepository.class);
        IProductPort port = mock(IProductPort.class);
        OrderService service = spy(new OrderService(repository, port));
        OrderEntity order = OrderEntity.builder()
                .userId("10001")
                .orderId("order-late-pay")
                .orderStatusVO(OrderStatusVO.PAY_WAIT)
                .marketType(MarketTypeVO.GROUP_BUY_MARKET.getCode())
                .build();
        when(repository.queryOrderByOrderId("order-late-pay")).thenReturn(order);
        when(port.settlementMarketPayOrder(eq("10001"), eq("order-late-pay"), any(Date.class)))
                .thenReturn(MarketSettlementResult.TERMINAL_REJECTED);
        doReturn(true).when(service).refundPayOrder("10001", "order-late-pay");

        service.changeOrderPaySuccess("order-late-pay", new Date());

        verify(repository).changeMarketOrderPaySuccess("order-late-pay");
        verify(service).refundPayOrder("10001", "order-late-pay");
        verify(repository, never()).markSettlementNotified("order-late-pay");
    }
}
