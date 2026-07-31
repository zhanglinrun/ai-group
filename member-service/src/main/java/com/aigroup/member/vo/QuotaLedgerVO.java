package com.aigroup.member.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QuotaLedgerVO {
    private String type;
    private Long amount;
    private String freezeId;
    private String abilityCode;
    private String traceId;
    private String remark;
    private LocalDateTime createdAt;
}
