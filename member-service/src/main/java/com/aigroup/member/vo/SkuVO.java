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
    /** 拼团映射：group_buy_market 商品ID（NULL=不支持拼团） */
    private String groupGoodsId;
    /** 拼团映射：group_buy_market 活动ID（NULL=不支持拼团） */
    private Long groupActivityId;
}
