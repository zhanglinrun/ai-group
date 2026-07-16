package com.aigroup.bff.client;

import com.aigroup.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

// url 为空时走 Nacos 服务发现（按 name=member 负载均衡）；local profile 设 ai-group.member.url 直连
@FeignClient(name = "member", url = "${ai-group.member.url:}")
public interface MemberFeignClient {

    @GetMapping("/api/member/skus")
    Result<List<Map<String, Object>>> listSkus();

    @GetMapping("/api/member/summary")
    Result<Map<String, Object>> summary();

    @GetMapping("/api/member/quota-ledger")
    Result<List<Map<String, Object>>> quotaLedger();

    @GetMapping("/internal/benefits/orders/{orderId}/status")
    Result<Map<String, String>> benefitStatus(@org.springframework.web.bind.annotation.PathVariable("orderId") String orderId);
}
