package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.order.adapter.port.IDemoGroupPort;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.dto.DemoFinalizeMarketPayOrderRequestDTO;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DemoGroupPort implements IDemoGroupPort {

    private final IGroupBuyMarketService groupBuyMarketService;

    public DemoGroupPort(IGroupBuyMarketService groupBuyMarketService) {
        this.groupBuyMarketService = groupBuyMarketService;
    }

    @Override
    public boolean finalizePaidGroup(String userId, String outTradeNo) {
        DemoFinalizeMarketPayOrderRequestDTO request = new DemoFinalizeMarketPayOrderRequestDTO();
        request.setUserId(userId);
        request.setOutTradeNo(outTradeNo);
        try {
            retrofit2.Response<Response<String>> httpResponse =
                    groupBuyMarketService.finalizeDemoMarketPayOrder(request).execute();
            Response<String> body = httpResponse.body();
            boolean accepted = httpResponse.isSuccessful() && body != null && "0000".equals(body.getCode());
            if (!accepted) {
                log.warn("demo group finalize rejected userId={} outTradeNo={} http={} body={}",
                        userId, outTradeNo, httpResponse.code(), body);
            }
            return accepted;
        } catch (Exception e) {
            log.warn("demo group finalize unavailable userId={} outTradeNo={} reason={}",
                    userId, outTradeNo, e.getMessage());
            return false;
        }
    }
}
