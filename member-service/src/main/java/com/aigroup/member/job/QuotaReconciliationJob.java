package com.aigroup.member.job;

import com.aigroup.member.service.QuotaReconciliationService;
import com.aigroup.member.service.QuotaReconciliationService.Result;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
public class QuotaReconciliationJob {
    private final QuotaReconciliationService reconciliationService;
    private final int pageSize;

    public QuotaReconciliationJob(QuotaReconciliationService reconciliationService,
                                  @Value("${ai-group.member.reconciliation-page-size:200}") int pageSize) {
        this.reconciliationService = reconciliationService;
        this.pageSize = pageSize;
    }

    @XxlJob("quotaReconciliationJob")
    public void reconcile() {
        LocalDate date = LocalDate.now();
        Result result = reconciliationService.check(date, pageSize);
        log.info("quota reconciliation completed, date={}, checked={}, ledgerMismatches={}, freezeMismatches={}",
                date, result.checked(), result.ledgerMismatches(), result.freezeMismatches());
    }
}
