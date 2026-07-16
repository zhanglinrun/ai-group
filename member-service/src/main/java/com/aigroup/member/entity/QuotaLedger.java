package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quota_ledger")
public class QuotaLedger {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private Long amount;
    private String freezeId;
    private String abilityCode;
    private String remark;
    private LocalDateTime createdAt;
}
