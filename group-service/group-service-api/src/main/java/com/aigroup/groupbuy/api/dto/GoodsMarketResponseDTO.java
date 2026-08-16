package com.aigroup.groupbuy.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @description 商品营销应答对象
 * @create 2025-02-02 12:20
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GoodsMarketResponseDTO {

    // 活动ID
    private Long activityId;
    // 活动类型（0经典折扣拼团、1阶梯额度拼团）
    private Integer activityType;
    // 当前活动成团人数目标；经典拼团也需要在大厅展示
    private Integer targetCount;
    // 商品信息
    private Goods goods;
    // 阶梯档位（人数 → 累计加赠额度），仅阶梯额度拼团有值，按人数升序
    private List<Tier> tiers;
    // 组队信息（1个个人的置顶、2个随机的「获取10个，随机取2个」）
    private List<Team> teamList;
    // 组队统计
    private TeamStatistic teamStatistic;

    /**
     * 阶梯档位：达到 targetCount 人时，在基础额度之上累计加赠 bonusQuota 额度
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Tier {
        // 档位序号（1..N，按人数升序）
        private Integer tierNo;
        // 档位名称，如 3人团/5人团/10人团
        private String tierName;
        // 达成该档位所需的成团人数
        private Integer targetCount;
        // 相对基础额度的累计加赠额度
        private Integer bonusQuota;
    }

    /**
     * 商品信息
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Goods {
        // 商品ID
        private String goodsId;
        // 原始价格
        private BigDecimal originalPrice;
        // 折扣金额
        private BigDecimal deductionPrice;
        // 支付价格
        private BigDecimal payPrice;
    }

    /**
     * 组队信息
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Team {
        // 用户ID
        private String userId;
        // 拼单组队ID
        private String teamId;
        // 活动ID
        private Long activityId;
        // 目标数量
        private Integer targetCount;
        // 完成数量
        private Integer completeCount;
        // 锁单数量
        private Integer lockCount;
        // 拼团开始时间 - 参与拼团时间
        private Date validStartTime;
        // 拼团结束时间 - 拼团有效时长
        private Date validEndTime;
        // 倒计时(字符串) validEndTime - validStartTime
        private String validTimeCountdown;
        /** 外部交易单号-确保外部调用唯一幂等 */
        private String outTradeNo;
        // 阶梯额度拼团：当前团已达档位序号（0 表示尚未达到任何档位）
        private Integer reachedTierNo;
        // 阶梯额度拼团：下一档位所需人数（null 表示已达最高档）
        private Integer nextTierTargetCount;
        // 阶梯额度拼团：最高档位人数（用于展示 X/最高档 进度）
        private Integer maxTierTargetCount;
        // 创建团队时的档位快照；已有团队展示必须优先使用它而不是活动实时档位
        private List<Tier> tiers;

        public static String differenceDateTime2Str(Date validStartTime, Date validEndTime) {
            if (validStartTime == null || validEndTime == null) {
                return "无效的时间";
            }

            long diffInMilliseconds = validEndTime.getTime() - validStartTime.getTime();

            if (diffInMilliseconds < 0) {
                return "已结束";
            }

            long seconds = TimeUnit.MILLISECONDS.toSeconds(diffInMilliseconds) % 60;
            long minutes = TimeUnit.MILLISECONDS.toMinutes(diffInMilliseconds) % 60;
            long hours = TimeUnit.MILLISECONDS.toHours(diffInMilliseconds) % 24;
            long days = TimeUnit.MILLISECONDS.toDays(diffInMilliseconds);

            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }

    }

    /**
     * 组队统计
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TeamStatistic {
        // 开团队伍数量
        private Integer allTeamCount;
        // 成团队伍数量
        private Integer allTeamCompleteCount;
        // 参团人数总量 - 一个商品的总参团人数
        private Integer allTeamUserCount;
    }

}
