package com.aigroup.paymall.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitEvent {

    private Long id;
    private String eventId;
    private String eventType;
    private Long userId;
    private String orderId;
    private String productCode;
    private Boolean eventPublished;
    private Long baseQuota;
    private Date createTime;
    private Date updateTime;

}
