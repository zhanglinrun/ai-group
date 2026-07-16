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
import retrofit2.Call;

import java.io.IOException;
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
    public void ambiguousTimeoutRecoversCommittedResultByBusinessKey() throws Exception {
        Call<Response<LockMarketPayOrderResponseDTO>> lockCall = call();
        when(lockCall.execute()).thenThrow(new IOException("response lost"));
        when(groupService.lockMarketPayOrder(any())).thenReturn(lockCall);

        Call<Response<LockMarketPayOrderResponseDTO>> queryCall = call();
        when(queryCall.execute()).thenReturn(retrofit2.Response.success(successBody()));
        when(groupService.queryMarketPayOrder(any())).thenReturn(queryCall);

        MarketPayDiscountEntity result = lock();

        assertNotNull(result);
        verify(groupService).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    @Test
    public void missingQueryResultRetriesOriginalIdempotencyKey() throws Exception {
        Call<Response<LockMarketPayOrderResponseDTO>> failedLock = call();
        when(failedLock.execute()).thenThrow(new IOException("connection reset"));
        Call<Response<LockMarketPayOrderResponseDTO>> successfulLock = call();
        when(successfulLock.execute()).thenReturn(retrofit2.Response.success(successBody()));
        when(groupService.lockMarketPayOrder(any())).thenReturn(failedLock, successfulLock);

        Call<Response<LockMarketPayOrderResponseDTO>> queryCall = call();
        when(queryCall.execute()).thenReturn(retrofit2.Response.success(Response.<LockMarketPayOrderResponseDTO>builder()
                .code("E0104").info("not found").build()));
        when(groupService.queryMarketPayOrder(any())).thenReturn(queryCall);

        assertNotNull(lock());
        verify(groupService, times(2)).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    @Test
    public void uniqueKeyRaceQueriesCommittedResultBeforeRetrying() throws Exception {
        Call<Response<LockMarketPayOrderResponseDTO>> lockCall = call();
        when(lockCall.execute()).thenReturn(retrofit2.Response.success(
                Response.<LockMarketPayOrderResponseDTO>builder()
                        .code("0003").info("unique-key race").build()));
        when(groupService.lockMarketPayOrder(any())).thenReturn(lockCall);

        Call<Response<LockMarketPayOrderResponseDTO>> queryCall = call();
        when(queryCall.execute()).thenReturn(retrofit2.Response.success(successBody()));
        when(groupService.queryMarketPayOrder(any())).thenReturn(queryCall);

        assertNotNull(lock());
        verify(groupService).lockMarketPayOrder(any(LockMarketPayOrderRequestDTO.class));
        verify(groupService).queryMarketPayOrder(any(QueryMarketPayOrderRequestDTO.class));
    }

    @Test
    public void explicitBusinessRejectionIsNotRetried() throws Exception {
        Call<Response<LockMarketPayOrderResponseDTO>> lockCall = call();
        when(lockCall.execute()).thenReturn(retrofit2.Response.success(Response.<LockMarketPayOrderResponseDTO>builder()
                .code("E0103").info("take limit reached").build()));
        when(groupService.lockMarketPayOrder(any())).thenReturn(lockCall);

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

    @SuppressWarnings("unchecked")
    private Call<Response<LockMarketPayOrderResponseDTO>> call() {
        return mock(Call.class);
    }
}
