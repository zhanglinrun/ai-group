package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quota_account")
public class QuotaAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long freeQuotaBalance;
    private Long paidQuotaBalance;
    private Long frozenBalance;
    private String lastFreeGrantMonth;
    private LocalDateTime updateTime;
}
