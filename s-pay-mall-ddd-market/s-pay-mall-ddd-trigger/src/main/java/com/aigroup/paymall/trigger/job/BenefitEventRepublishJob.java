package com.aigroup.paymall.trigger.job;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Slf4j
@Component
public class BenefitEventRepublishJob {

    @Resource
    private IBenefitEventService benefitEventService;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void exec() {
        try {
            int count = benefitEventService.republishPendingEvents();
            if (count > 0) {
                log.info("benefit event republish job resent {} events", count);
            }
        } catch (Exception e) {
            log.error("benefit event republish job failed", e);
        }
    }

}
