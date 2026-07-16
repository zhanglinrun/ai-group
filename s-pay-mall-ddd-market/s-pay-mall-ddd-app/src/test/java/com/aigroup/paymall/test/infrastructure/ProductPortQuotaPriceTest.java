package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.infrastructure.adapter.port.ProductPort;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.LockMarketPayOrderResponseDTO;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProductPortQuotaPriceTest {

    @Test
    public void groupOrderKeepsTrustedPackagePriceAndIgnoresLegacyCashDiscount() throws Exception {
        IGroupBuyMarketService groupService = mock(IGroupBuyMarketService.class);
        @SuppressWarnings("unchecked")
        Call<Response<LockMarketPayOrderResponseDTO>> call = mock(Call.class);
        when(groupService.lockMarketPayOrder(any())).thenReturn(call);
        LockMarketPayOrderResponseDTO groupQuote = new LockMarketPayOrderResponseDTO();
        groupQuote.setOriginalPrice(new BigDecimal("12.00"));
        groupQuote.setDeductionPrice(BigDecimal.ZERO);
        groupQuote.setPayPrice(new BigDecimal("12.00"));
        when(call.execute()).thenReturn(retrofit2.Response.success(Response.<LockMarketPayOrderResponseDTO>builder()
                .code("0000").data(groupQuote).build()));

        ProductPort port = new ProductPort(mock(ProductRPC.class), groupService);
        MarketPayDiscountEntity quote = port.lockMarketPayOrder(
                "u1", null, 100201L, "9890002", "order-1", new BigDecimal("12.00"));

        assertEquals(0, new BigDecimal("12.00").compareTo(quote.getOriginalPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(quote.getDeductionPrice()));
        assertEquals(0, new BigDecimal("12.00").compareTo(quote.getPayPrice()));

        ArgumentCaptor<LockMarketPayOrderRequestDTO> request = ArgumentCaptor.forClass(LockMarketPayOrderRequestDTO.class);
        verify(groupService).lockMarketPayOrder(request.capture());
        assertEquals(0, new BigDecimal("12.00").compareTo(request.getValue().getOrderPrice()));
    }
}
