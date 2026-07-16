package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.api.response.Response;
import com.aigroup.paymall.domain.order.adapter.port.IDemoGroupPort;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.http.DemoPayController;
import com.aigroup.paymall.trigger.http.support.GatewayUserResolver;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DemoPayControllerTest {

    private IOrderService orderService;
    private IDemoGroupPort demoGroupPort;
    private GatewayUserResolver userResolver;
    private DemoPayController controller;
    private MockHttpServletRequest request;

    @Before
    public void setUp() {
        orderService = mock(IOrderService.class);
        demoGroupPort = mock(IDemoGroupPort.class);
        userResolver = mock(GatewayUserResolver.class);
        controller = new DemoPayController(orderService, demoGroupPort, userResolver);
        request = new MockHttpServletRequest();
        when(userResolver.resolveUserId(request, null)).thenReturn("u1");
    }

    @Test
    public void onlyOrderOwnerCanCompleteDemoPayment() {
        when(orderService.queryOrderByOrderId("o1")).thenReturn(order("u2", OrderStatusVO.PAY_WAIT, 1));

        Response<String> response = controller.complete("o1", request);

        assertEquals("0002", response.getCode());
        verify(orderService, never()).changeOrderPaySuccess(any(), any());
    }

    @Test
    public void payableGroupUsesNormalPaySuccessThenFinalizesGroup() {
        when(orderService.queryOrderByOrderId("o1")).thenReturn(order("u1", OrderStatusVO.PAY_WAIT, 1));
        when(demoGroupPort.finalizePaidGroup("u1", "o1")).thenReturn(true);

        Response<String> response = controller.complete("o1", request);

        assertEquals("0000", response.getCode());
        assertEquals("GROUP_FINALIZED", response.getData());
        verify(orderService).changeOrderPaySuccess(eq("o1"), any());
        verify(demoGroupPort).finalizePaidGroup("u1", "o1");
    }

    @Test
    public void repeatedCompletedGroupRequestIsIdempotent() {
        when(orderService.queryOrderByOrderId("o1")).thenReturn(order("u1", OrderStatusVO.DEAL_DONE, 1));
        when(demoGroupPort.finalizePaidGroup("u1", "o1")).thenReturn(true);

        Response<String> response = controller.complete("o1", request);

        assertEquals("0000", response.getCode());
        assertEquals("GROUP_FINALIZED", response.getData());
        verify(orderService, never()).changeOrderPaySuccess(any(), any());
        verify(demoGroupPort).finalizePaidGroup("u1", "o1");
    }

    @Test
    public void closedOrderCannotBeCompleted() {
        when(orderService.queryOrderByOrderId("o1")).thenReturn(order("u1", OrderStatusVO.CLOSE, 0));

        Response<String> response = controller.complete("o1", request);

        assertEquals("0002", response.getCode());
        verify(demoGroupPort, never()).finalizePaidGroup(any(), any());
    }

    private OrderEntity order(String userId, OrderStatusVO status, int marketType) {
        return OrderEntity.builder()
                .userId(userId)
                .orderId("o1")
                .orderStatusVO(status)
                .marketType(marketType == 1
                        ? MarketTypeVO.GROUP_BUY_MARKET.getCode() : MarketTypeVO.NO_MARKET.getCode())
                .build();
    }
}
