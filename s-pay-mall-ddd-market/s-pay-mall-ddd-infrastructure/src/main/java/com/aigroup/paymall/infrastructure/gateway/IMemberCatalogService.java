package com.aigroup.paymall.infrastructure.gateway;

import com.aigroup.paymall.infrastructure.gateway.dto.MemberSkuDTO;
import com.aigroup.paymall.infrastructure.gateway.response.MemberResult;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

public interface IMemberCatalogService {

    @GET("internal/skus/by-goods/{goodsId}")
    Call<MemberResult<MemberSkuDTO>> queryEnabledSkuByGoodsId(@Path("goodsId") String goodsId);
}
