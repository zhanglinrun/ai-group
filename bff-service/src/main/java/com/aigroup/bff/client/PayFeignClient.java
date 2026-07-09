package com.aigroup.bff.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "pay", url = "${ai-group.pay.url:http://127.0.0.1:8070}")
public interface PayFeignClient {

    @PostMapping("/api/v1/alipay/query_user_order_list")
    Map<String, Object> queryUserOrderList(@RequestBody Map<String, Object> request);
}
