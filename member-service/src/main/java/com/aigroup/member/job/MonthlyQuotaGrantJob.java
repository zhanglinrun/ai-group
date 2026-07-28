package com.aigroup.member.job;

import com.aigroup.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyQuotaGrantJob {

    private final MemberService memberService;

    @XxlJob("monthlyQuotaGrantJob")
    public void grantMonthlyQuota() {
        int count = memberService.grantMonthlyQuota();
        if (count > 0) {
            log.info("Monthly quota grant completed, users={}", count);
        }
    }
}
