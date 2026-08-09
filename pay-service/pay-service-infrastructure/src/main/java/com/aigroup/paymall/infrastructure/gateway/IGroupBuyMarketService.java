package com.aigroup.paymall.infrastructure.gateway;

import com.aigroup.paymall.infrastructure.gateway.dto.*;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 拼团营销服务 Feign 客户端。url 为空时走 Nacos 服务发现（按 name=group 负载均衡）；
 * local profile 设 app.config.group-service.api-url 直连。
 */
@FeignClient(name = "group-service", url = "${app.config.group-service.api-url:}")
public interface IGroupBuyMarketService {

    /**
     * 营销锁单
     *
     * @param requestDTO 锁单商品信息
     * @return 锁单结果信息
     */
    @PostMapping("api/v1/gbm/trade/lock_market_pay_order")
    Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(@RequestBody LockMarketPayOrderRequestDTO requestDTO);

    @PostMapping("api/v1/gbm/trade/query_market_pay_order")
    Response<LockMarketPayOrderResponseDTO> queryMarketPayOrder(@RequestBody QueryMarketPayOrderRequestDTO requestDTO);

    /**
     * 营销结算
     *
     * @param requestDTO 结算商品信息
     * @return 结算结果信息
     */
    @PostMapping("api/v1/gbm/trade/settlement_market_pay_order")
    Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(@RequestBody SettlementMarketPayOrderRequestDTO requestDTO);

    /**
     * 营销拼团退单
     *
     * @param requestDTO 退单请求信息
     * @return 退单结果信息
     */
    @PostMapping("api/v1/gbm/trade/refund_market_pay_order")
    Response<RefundMarketPayOrderResponseDTO> refundMarketPayOrder(@RequestBody RefundMarketPayOrderRequestDTO requestDTO);

    /** Dev-only endpoint. The group application does not register this mapping outside dev profile. */
    @PostMapping("api/v1/gbm/trade/demo_finalize_market_pay_order")
    Response<String> finalizeDemoMarketPayOrder(@RequestBody DemoFinalizeMarketPayOrderRequestDTO requestDTO);

}
