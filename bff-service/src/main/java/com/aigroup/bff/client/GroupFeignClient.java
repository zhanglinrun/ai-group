package com.aigroup.bff.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "group", url = "${ai-group.group.url:http://127.0.0.1:8091}")
public interface GroupFeignClient {

    @PostMapping("/api/v1/gbm/index/query_group_buy_market_config")
    Map<String, Object> queryGroupBuyMarketConfig(@RequestBody Map<String, Object> request);
}
