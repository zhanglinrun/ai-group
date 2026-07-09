package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("member_account")
public class MemberAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String tier;
    private LocalDateTime startAt;
    private LocalDateTime expireAt;
    private String lastPeriodGrantMonth;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
