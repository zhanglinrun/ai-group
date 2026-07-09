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
    /** 客户端幂等键（agent 请求ID）；同一 requestId 重复预扣返回同一 freezeId，避免重试重复冻结 */
    private String requestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
