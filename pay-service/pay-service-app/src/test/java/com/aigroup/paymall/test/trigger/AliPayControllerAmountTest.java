package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.api.dto.CreatePayQrResponseDTO;
import com.aigroup.paymall.api.dto.CreatePayRequestDTO;
import com.aigroup.paymall.api.response.Response;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.http.AliPayController;
import com.aigroup.paymall.trigger.http.support.GatewayUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AliPayControllerAmountTest {

    private IOrderService orderService;
    private GatewayUserResolver userResolver;
    private AliPayController controller;

    @Before
    public void setUp() {
        orderService = mock(IOrderService.class);
        userResolver = mock(GatewayUserResolver.class);
        controller = new AliPayController();
        ReflectionTestUtils.setField(controller, "orderService", orderService);
        ReflectionTestUtils.setField(controller, "gatewayUserResolver", userResolver);
    }

    @Test
    public void qrDialogUsesPersistedChargeAmountInsteadOfPreviewDiscount() throws Exception {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(userResolver.resolveUserId(eq(servletRequest), isNull())).thenReturn("u1");

        PayOrderEntity created = new PayOrderEntity();
        created.setOrderId("o1");
        when(orderService.createOrder(any())).thenReturn(created);
        when(orderService.prepareTradeQrCode("o1")).thenReturn("qr-code");

        OrderEntity persisted = new OrderEntity();
        persisted.setTotalAmount(new BigDecimal("12.00"));
        persisted.setMarketDeductionAmount(new BigDecimal("10.00"));
        persisted.setPayAmount(new BigDecimal("12.00"));
        when(orderService.queryOrderByOrderId("o1")).thenReturn(persisted);

        CreatePayRequestDTO request = new CreatePayRequestDTO();
        request.setRequestId("pay-request-1");
        request.setProductId("10001");
        request.setProductCode("QUOTA_LIGHT");
        request.setMarketType(0);

        Response<CreatePayQrResponseDTO> response = controller.createPayQrCode(request, servletRequest);

        assertEquals("0000", response.getCode());
        assertEquals(new BigDecimal("12.00"), response.getData().getAmount());
    }

    @Test
    public void idempotentReplayReturnsPersistedQrWithoutCallingProviderAgain() throws Exception {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(userResolver.resolveUserId(eq(servletRequest), isNull())).thenReturn("u1");

        PayOrderEntity replay = new PayOrderEntity();
        replay.setOrderId("o-replay");
        replay.setIdempotentReplay(true);
        when(orderService.createOrder(any())).thenReturn(replay);

        OrderEntity persisted = new OrderEntity();
        persisted.setPayUrl("https://qr.alipay.example/o-replay");
        persisted.setPayAmount(new BigDecimal("10.00"));
        when(orderService.queryOrderByOrderId("o-replay")).thenReturn(persisted);

        CreatePayRequestDTO request = new CreatePayRequestDTO();
        request.setRequestId("pay-request-replay");
        request.setProductId("10001");
        request.setProductCode("QUOTA_LIGHT");
        request.setMarketType(0);

        Response<CreatePayQrResponseDTO> response = controller.createPayQrCode(request, servletRequest);

        assertEquals("0000", response.getCode());
        assertEquals("o-replay", response.getData().getOrderId());
        assertEquals("https://qr.alipay.example/o-replay", response.getData().getQrCode());
        verify(orderService, never()).prepareTradeQrCode("o-replay");
    }
}
