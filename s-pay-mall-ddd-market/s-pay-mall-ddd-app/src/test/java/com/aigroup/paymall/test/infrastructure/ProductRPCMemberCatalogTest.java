package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.infrastructure.gateway.IMemberCatalogService;
import com.aigroup.paymall.infrastructure.gateway.PayProductCatalogProperties;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.MemberSkuDTO;
import com.aigroup.paymall.infrastructure.gateway.dto.ProductDTO;
import com.aigroup.paymall.infrastructure.gateway.response.MemberResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProductRPCMemberCatalogTest {

    @Test
    public void memberResultIgnoresPlatformTimestampMetadata() throws Exception {
        String json = "{\"code\":200,\"message\":\"ok\",\"timestamp\":1784100000000,"
                + "\"data\":{\"code\":\"QUOTA_LIGHT\",\"name\":\"轻享额度包\","
                + "\"price\":12.00,\"baseQuota\":60,\"groupGoodsId\":\"9890002\"}}";

        MemberResult<MemberSkuDTO> decoded = new ObjectMapper().readValue(
                json, new TypeReference<MemberResult<MemberSkuDTO>>() { });

        assertEquals(Integer.valueOf(200), decoded.getCode());
        assertEquals("QUOTA_LIGHT", decoded.getData().getCode());
    }

    @Test
    public void usesEnabledMemberSkuAsTrustedOrderSource() throws Exception {
        IMemberCatalogService service = mock(IMemberCatalogService.class);
        @SuppressWarnings("unchecked")
        Call<MemberResult<MemberSkuDTO>> call = mock(Call.class);
        when(service.queryEnabledSkuByGoodsId("9890002")).thenReturn(call);

        MemberSkuDTO sku = new MemberSkuDTO();
        sku.setCode("QUOTA_LIGHT");
        sku.setName("轻量额度包");
        sku.setPrice(new BigDecimal("12.00"));
        sku.setBaseQuota(60L);
        sku.setGroupGoodsId("9890002");
        MemberResult<MemberSkuDTO> result = new MemberResult<>();
        result.setCode(200);
        result.setData(sku);
        when(call.execute()).thenReturn(Response.success(result));

        ProductDTO product = new ProductRPC(new PayProductCatalogProperties(), service)
                .queryProductByProductId("9890002");

        assertEquals("QUOTA_LIGHT", product.getProductCode());
        assertEquals(Long.valueOf(60L), product.getBaseQuota());
        assertEquals(0, new BigDecimal("12.00").compareTo(product.getPrice()));
    }

    @Test
    public void failsClosedWhenMemberCatalogIsUnavailable() throws Exception {
        IMemberCatalogService service = mock(IMemberCatalogService.class);
        @SuppressWarnings("unchecked")
        Call<MemberResult<MemberSkuDTO>> call = mock(Call.class);
        when(service.queryEnabledSkuByGoodsId("9890002")).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("down"));

        ProductRPC rpc = new ProductRPC(new PayProductCatalogProperties(), service);

        assertThrows(IllegalStateException.class, () -> rpc.queryProductByProductId("9890002"));
    }
}
