package com.aigroup.member.job;

import com.aigroup.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyQuotaGrantJob {

    private final MemberService memberService;

    @Scheduled(cron = "${ai-group.member.monthly-grant-cron:0 0 1 * * ?}")
    public void grantMonthlyQuota() {
        int count = memberService.grantMonthlyQuota();
        if (count > 0) {
            log.info("Monthly quota grant completed, users={}", count);
        }
    }
}
