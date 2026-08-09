package com.aigroup.paymall.infrastructure.gateway;

import com.aigroup.paymall.infrastructure.gateway.dto.MemberSkuDTO;
import com.aigroup.paymall.infrastructure.gateway.response.MemberResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 会员额度目录服务 Feign 客户端。url 为空时走 Nacos 服务发现（按 name=member 负载均衡）；
 * local profile 设 app.config.member-service.api-url 直连。
 */
@FeignClient(name = "member", url = "${app.config.member-service.api-url:}")
public interface IMemberCatalogService {

    @GetMapping("internal/skus/by-goods/{goodsId}")
    MemberResult<MemberSkuDTO> queryEnabledSkuByGoodsId(@PathVariable("goodsId") String goodsId);
}
