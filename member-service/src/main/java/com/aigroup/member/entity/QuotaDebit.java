package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quota_debit")
public class QuotaDebit {
    @TableId
    private String debitId;
    private Long userId;
    private Long amount;
    private Long freeAmount;
    private Long paidAmount;
    private Long requestedAmount;
    private String abilityCode;
    private String status;
    private String requestId;
    private String traceId;
    private String requestFingerprint;
    private String ownerService;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
