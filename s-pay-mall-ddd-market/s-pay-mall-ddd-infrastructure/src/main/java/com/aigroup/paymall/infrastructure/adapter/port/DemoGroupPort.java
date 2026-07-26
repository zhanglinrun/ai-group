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
            Response<String> body = groupBuyMarketService.finalizeDemoMarketPayOrder(request);
            boolean accepted = body != null && "0000".equals(body.getCode());
            if (!accepted) {
                log.warn("demo group finalize rejected userId={} outTradeNo={} body={}",
                        userId, outTradeNo, body);
            }
            return accepted;
        } catch (Exception e) {
            log.warn("demo group finalize unavailable userId={} outTradeNo={} reason={}",
                    userId, outTradeNo, e.getMessage());
            return false;
        }
    }
}
