package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.adapter.port.MarketSettlementResult;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.infrastructure.adapter.port.ProductPort;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.dto.RefundMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.SettlementMarketPayOrderResponseDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.SettlementMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProductPortSettlementResultTest {

    private IGroupBuyMarketService groupService;
    private IOrderRepository orders;
    private ProductPort port;

    @Before
    public void setUp() {
        groupService = mock(IGroupBuyMarketService.class);
        port = new ProductPort(mock(ProductRPC.class), groupService);
        orders = mock(IOrderRepository.class);
        ReflectionTestUtils.setField(port, "orderRepository", orders);
        ReflectionTestUtils.setField(port, "source", "s99");
        ReflectionTestUtils.setField(port, "chanel", "c99");
        when(orders.queryOrderByOrderId("order-1")).thenReturn(OrderEntity.builder()
                .userId("10001").marketType(1).groupSource("s01").groupChannel("c01").build());
    }

    @Test
    public void mapsFinalizedTeamToTerminalRejection() {
        when(groupService.settlementMarketPayOrder(any())).thenReturn(responseWithCode("E0107"));

        assertEquals(MarketSettlementResult.TERMINAL_REJECTED,
                port.settlementMarketPayOrder("10001", "order-1", new Date()));
    }

    @Test
    public void mapsTransientBusinessFailureToRetryable() {
        when(groupService.settlementMarketPayOrder(any())).thenReturn(responseWithCode("0004"));

        assertEquals(MarketSettlementResult.RETRYABLE_FAILURE,
                port.settlementMarketPayOrder("10001", "order-1", new Date()));
    }

    @Test
    public void settlementUsesLockedChannelAfterConfigurationRotates() {
        when(groupService.settlementMarketPayOrder(any())).thenReturn(responseWithCode("0000"));
        assertEquals(MarketSettlementResult.ACKNOWLEDGED,
                port.settlementMarketPayOrder("10001", "order-1", new Date()));
        ArgumentCaptor<SettlementMarketPayOrderRequestDTO> sent = ArgumentCaptor.forClass(SettlementMarketPayOrderRequestDTO.class);
        verify(groupService).settlementMarketPayOrder(sent.capture());
        assertEquals("s01", sent.getValue().getSource());
        assertEquals("c01", sent.getValue().getChannel());
    }

    @Test
    public void refundUsesLockedChannelAfterConfigurationRotates() {
        port.refundMarketPayOrder("10001", "order-1");
        ArgumentCaptor<RefundMarketPayOrderRequestDTO> sent = ArgumentCaptor.forClass(RefundMarketPayOrderRequestDTO.class);
        verify(groupService).refundMarketPayOrder(sent.capture());
        assertEquals("s01", sent.getValue().getSource());
        assertEquals("c01", sent.getValue().getChannel());
    }

    @Test
    public void missingLockIdentityNeverCallsGroup() {
        when(orders.queryOrderByOrderId("order-1")).thenReturn(null);
        assertEquals(MarketSettlementResult.RETRYABLE_FAILURE,
                port.settlementMarketPayOrder("10001", "order-1", new Date()));
        assertFalse(port.refundMarketPayOrder("10001", "order-1"));
        verify(groupService, never()).settlementMarketPayOrder(any());
        verify(groupService, never()).refundMarketPayOrder(any());
    }

    private Response<SettlementMarketPayOrderResponseDTO> responseWithCode(String code) {
        return Response.<SettlementMarketPayOrderResponseDTO>builder().code(code).build();
    }
}
