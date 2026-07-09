package com.aigroup.member.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SkuVO {
    private String code;
    private String name;
    private BigDecimal price;
    private Integer periodQuota;
    private Integer topupQuota;
    private Integer memberDays;
    private String tier;
    private String skuType;
}
