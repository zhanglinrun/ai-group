package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.adapter.port.MarketSettlementResult;
import com.aigroup.paymall.infrastructure.adapter.port.ProductPort;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.SettlementMarketPayOrderResponseDTO;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    private Response<SettlementMarketPayOrderResponseDTO> responseWithCode(String code) {
        return Response.<SettlementMarketPayOrderResponseDTO>builder().code(code).build();
    }
}
