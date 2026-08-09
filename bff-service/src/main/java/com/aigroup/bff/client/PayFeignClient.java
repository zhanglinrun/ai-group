package com.aigroup.bff.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "pay-service", url = "${ai-group.pay.url:}")
public interface PayFeignClient {

    @PostMapping("/api/pay/orders/page")
    Map<String, Object> queryUserOrderList(@RequestBody Map<String, Object> request);
}
