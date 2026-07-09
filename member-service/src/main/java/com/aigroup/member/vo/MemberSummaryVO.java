package com.aigroup.member.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemberSummaryVO {
    private Long userId;
    private String tier;
    private LocalDateTime startAt;
    private LocalDateTime expireAt;
    private Integer periodQuotaBalance;
    private Integer topupQuotaBalance;
    private Integer frozenBalance;
    private Integer availableQuota;
}
