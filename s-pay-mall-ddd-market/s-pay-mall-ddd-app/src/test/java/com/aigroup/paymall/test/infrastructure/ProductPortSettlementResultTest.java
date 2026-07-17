package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.adapter.port.MarketSettlementResult;
import com.aigroup.paymall.infrastructure.adapter.port.ProductPort;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.SettlementMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.SettlementMarketPayOrderResponseDTO;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProductPortSettlementResultTest {

    private IGroupBuyMarketService groupService;
    private ProductPort port;

    @Before
    public void setUp() {
        groupService = mock(IGroupBuyMarketService.class);
        port = new ProductPort(mock(ProductRPC.class), groupService);
        ReflectionTestUtils.setField(port, "source", "s01");
        ReflectionTestUtils.setField(port, "chanel", "c01");
    }

    @Test
    public void mapsFinalizedTeamToTerminalRejection() throws Exception {
        Call<Response<SettlementMarketPayOrderResponseDTO>> call = callWithCode("E0107");
        when(groupService.settlementMarketPayOrder(any(SettlementMarketPayOrderRequestDTO.class)))
                .thenReturn(call);

        assertEquals(MarketSettlementResult.TERMINAL_REJECTED,
                port.settlementMarketPayOrder("10001", "order-1", new Date()));
    }

    @Test
    public void mapsTransientBusinessFailureToRetryable() throws Exception {
        Call<Response<SettlementMarketPayOrderResponseDTO>> call = callWithCode("0004");
        when(groupService.settlementMarketPayOrder(any(SettlementMarketPayOrderRequestDTO.class)))
                .thenReturn(call);

        assertEquals(MarketSettlementResult.RETRYABLE_FAILURE,
                port.settlementMarketPayOrder("10001", "order-1", new Date()));
    }

    @SuppressWarnings("unchecked")
    private Call<Response<SettlementMarketPayOrderResponseDTO>> callWithCode(String code) throws Exception {
        Call<Response<SettlementMarketPayOrderResponseDTO>> call = mock(Call.class);
        when(call.execute()).thenReturn(retrofit2.Response.success(
                Response.<SettlementMarketPayOrderResponseDTO>builder().code(code).build()));
        return call;
    }
}
