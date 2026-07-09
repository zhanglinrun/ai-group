package com.aigroup.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("product_sku")
public class ProductSku {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private BigDecimal price;
    private Integer periodQuota;
    private Integer topupQuota;
    private Integer memberDays;
    private String tier;
    private String skuType;
    private Integer status;
    /** 拼团映射：group_buy_market 商品ID（NULL=不支持拼团） */
    private String groupGoodsId;
    /** 拼团映射：group_buy_market 活动ID（NULL=不支持拼团） */
    private Long groupActivityId;
}
