package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.infrastructure.adapter.port.ProductPort;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.LockMarketPayOrderResponseDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.QueryMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProductPortLockRecoveryTest {

    private IGroupBuyMarketService groupService;
    private ProductPort port;

    @Before
    public void setUp() {
        groupService = mock(IGroupBuyMarketService.class);
        port = new ProductPort(mock(ProductRPC.class), groupService);
        ReflectionTestUtils.setField(port, "source", "s01");
        ReflectionTestUtils.setField(port, "chanel", "c01");
        ReflectionTestUtils.setField(port, "lockMaxAttempts", 3);
        ReflectionTestUtils.setField(port, "lockRetryBackoffMillis", 0L);
    }

    @Test
    public void ambiguousTimeoutRecoversCommittedResultByBusinessKey() {
        when(groupService.lockMarketPayOrder(any())).thenThrow(new RuntimeException("response lost"));
        when(groupService.queryMarketPayOrder(any())).thenReturn(successBody());

        MarketPayDiscountEntity result = lock();

        assertNotNull(result);
        verify(groupService).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    @Test
    public void missingQueryResultRetriesOriginalIdempotencyKey() {
        when(groupService.lockMarketPayOrder(any()))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(successBody());
        when(groupService.queryMarketPayOrder(any())).thenReturn(
                Response.<LockMarketPayOrderResponseDTO>builder()
                        .code("E0104").info("not found").build());

        assertNotNull(lock());
        verify(groupService, times(2)).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    @Test
    public void uniqueKeyRaceQueriesCommittedResultBeforeRetrying() {
        when(groupService.lockMarketPayOrder(any())).thenReturn(
                Response.<LockMarketPayOrderResponseDTO>builder()
                        .code("0003").info("unique-key race").build());
        when(groupService.queryMarketPayOrder(any())).thenReturn(successBody());

        assertNotNull(lock());
        verify(groupService).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    @Test
    public void explicitBusinessRejectionIsNotRetried() {
        when(groupService.lockMarketPayOrder(any())).thenReturn(
                Response.<LockMarketPayOrderResponseDTO>builder()
                        .code("E0103").info("take limit reached").build());

        assertNull(lock());
        verify(groupService).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService, never()).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    private MarketPayDiscountEntity lock() {
        return port.lockMarketPayOrder("u1", null, 100201L, "9890002", "order-1",
                new BigDecimal("12.00"));
    }

    private Response<LockMarketPayOrderResponseDTO> successBody() {
        return Response.<LockMarketPayOrderResponseDTO>builder()
                .code("0000")
                .data(LockMarketPayOrderResponseDTO.builder()
                        .orderId("group-order-1")
                        .teamId("team-1")
                        .originalPrice(new BigDecimal("12.00"))
                        .deductionPrice(BigDecimal.ZERO)
                        .payPrice(new BigDecimal("12.00"))
                        .tradeOrderStatus(0)
                        .build())
                .build();
    }
}
