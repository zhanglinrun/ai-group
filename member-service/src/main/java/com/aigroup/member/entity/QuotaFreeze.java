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
    private Integer amount;
    private String abilityCode;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
