package com.aigroup.groupbuy.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @description 拼团阶梯档位（人数档位 → 累计加赠额度），用于阶梯额度拼团
 * @create 2026-07-10
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyActivityTier {

    /** 自增ID */
    private Long id;
    /** 活动ID */
    private Long activityId;
    /** 档位序号（1..N，按人数升序） */
    private Integer tierNo;
    /** 档位名称，如 3人团/5人团/10人团 */
    private String tierName;
    /** 达成该档位所需的成团人数 */
    private Integer targetCount;
    /** 达成该档位时相对基础额度的累计加赠额度（叠加在 SKU 基础额度之上） */
    private Integer bonusQuota;
    /** 状态（0停用、1生效） */
    private Integer status;
    /** 创建时间 */
    private Date createTime;
    /** 更新时间 */
    private Date updateTime;

    public static String cacheRedisKey(Long activityId) {
        return "group_buy_market_com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivityTier_" + activityId;
    }

}
