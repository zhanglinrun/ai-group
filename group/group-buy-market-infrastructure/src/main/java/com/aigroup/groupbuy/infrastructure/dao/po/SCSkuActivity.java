package com.aigroup.groupbuy.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 渠道商品活动配置关联表
 * @create 2025-01-01 09:27
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SCSkuActivity {

    /** 自增ID */
    private Long id;
    /** 渠道 */
    private String source;
    /** 来源 */
    private String channel;
    /** 活动ID */
    private Long activityId;
    /** 商品ID */
    private String goodsId;
    /** 创建时间 */
    private Date createTime;
    /** 更新时间 */
    private Date updateTime;

}
