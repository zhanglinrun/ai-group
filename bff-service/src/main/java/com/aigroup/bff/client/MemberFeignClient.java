package com.aigroup.bff.client;

import com.aigroup.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@FeignClient(name = "member", url = "${ai-group.member.url:http://127.0.0.1:8082}")
public interface MemberFeignClient {

    @GetMapping("/api/member/skus")
    Result<List<Map<String, Object>>> listSkus();

    @GetMapping("/api/member/summary")
    Result<Map<String, Object>> summary();

    @GetMapping("/internal/benefits/orders/{orderId}/status")
    Result<Map<String, String>> benefitStatus(@org.springframework.web.bind.annotation.PathVariable("orderId") String orderId);
}
