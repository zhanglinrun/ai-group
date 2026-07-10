package com.aigroup.paymall.domain.benefit.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitEventEntity {

    private Long id;
    private String eventId;
    private String eventType;
    private Long userId;
    private String orderId;
    private String productCode;
    private Boolean eventPublished;
    /** 阶梯拼团加赠额度（随成团结算透传，用于 member 在基础额度上叠加发放） */
    private Integer bonusQuota;
    private Date createTime;
    private Date updateTime;

}
