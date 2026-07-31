package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quota_freeze")
public class QuotaFreeze {
    @TableId
    private String freezeId;
    private Long userId;
    private Long amount;
    private Long freeAmount;
    private Long paidAmount;
    private Long settledAmount;
    /** Original reservation upper bound, retained for idempotency validation. */
    private Long requestedAmount;
    /** Original minimum acceptable reservation, retained for idempotency validation. */
    private Long minAmount;
    private String abilityCode;
    private String status;
    /** 客户端幂等键（agent 请求ID）；同一 requestId 重复预扣返回同一 freezeId，避免重试重复冻结 */
    private String requestId;
    /** Immutable distributed trace copied from the owning Agent Run. */
    private String traceId;
    /** Stable hash of the canonical reservation request. */
    private String requestFingerprint;
    /** Service that durably owns terminal settlement; e.g. ai-agent. */
    private String ownerService;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
