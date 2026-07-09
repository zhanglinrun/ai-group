package org.wwz.ai.application.agent.quota;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "member-quota", url = "${ai-group.member-service.base-url:http://127.0.0.1:8082}")
public interface MemberQuotaFeignClient {

    @PostMapping("/internal/quota/freeze")
    MemberQuotaResult<QuotaFreezeVO> freeze(@RequestBody QuotaFreezeRequest request);

    @PostMapping("/internal/quota/confirm")
    MemberQuotaResult<Void> confirm(@RequestBody QuotaFreezeActionRequest request);

    @PostMapping("/internal/quota/release")
    MemberQuotaResult<Void> release(@RequestBody QuotaFreezeActionRequest request);
}
