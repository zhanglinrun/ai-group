package com.linrun.agent.infrastructure.gateway.quota;

import com.linrun.agent.infrastructure.gateway.quota.dto.MemberQuotaResult;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeActionRequest;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeRequest;
import com.linrun.agent.infrastructure.gateway.quota.dto.QuotaFreezeVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "member-quota",
        url = "${ai-group.member-service.base-url:http://127.0.0.1:18082}",
        configuration = MemberQuotaFeignConfiguration.class
)
public interface MemberQuotaFeignClient {

    @PostMapping("/internal/quota/freeze")
    MemberQuotaResult<QuotaFreezeVO> freeze(@RequestBody QuotaFreezeRequest request);

    @PostMapping("/internal/quota/confirm")
    MemberQuotaResult<QuotaFreezeVO> confirm(@RequestBody QuotaFreezeActionRequest request);

    @PostMapping("/internal/quota/release")
    MemberQuotaResult<QuotaFreezeVO> release(@RequestBody QuotaFreezeActionRequest request);

    @GetMapping("/internal/quota/freezes/{freezeId}")
    MemberQuotaResult<QuotaFreezeVO> findByFreezeId(@PathVariable("freezeId") String freezeId);

    @GetMapping("/internal/quota/freezes/by-request")
    MemberQuotaResult<QuotaFreezeVO> findByRequest(@RequestParam("userId") Long userId,
                                                   @RequestParam("requestId") String requestId);
}
