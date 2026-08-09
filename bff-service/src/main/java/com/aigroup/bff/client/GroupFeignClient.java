package com.aigroup.bff.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "group-service", url = "${ai-group.group.url:}")
public interface GroupFeignClient {

    @PostMapping("/api/group/activities")
    Map<String, Object> queryGroupBuyMarketConfig(@RequestBody Map<String, Object> request);
}
