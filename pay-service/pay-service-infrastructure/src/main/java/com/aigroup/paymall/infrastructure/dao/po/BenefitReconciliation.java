package com.aigroup.paymall.infrastructure.dao.po;

import lombok.Data;

@Data
public class BenefitReconciliation {
    private String eventId;
    private String outcome;
    private int replayAttempts;
}
