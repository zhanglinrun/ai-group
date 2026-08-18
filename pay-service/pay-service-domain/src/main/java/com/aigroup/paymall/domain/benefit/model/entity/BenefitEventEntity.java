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
    /** 下单时基础额度快照（整额度点） */
    private Long baseQuota;
    private Date createTime;
    private Date updateTime;

}
