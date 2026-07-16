package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("benefit_grant_event")
public class BenefitGrantEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String idempotencyKey;
    private Long userId;
    private String orderId;
    private String eventType;
    private String productCode;
    private String status;
    private Long grantedQuota;
    private LocalDateTime createdAt;
}
