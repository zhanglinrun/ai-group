package com.aigroup.paymall.infrastructure.gateway.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MemberSkuDTO {
    private String code;
    private String name;
    private BigDecimal price;
    private Long baseQuota;
    private String groupGoodsId;
    private Long groupActivityId;
}
