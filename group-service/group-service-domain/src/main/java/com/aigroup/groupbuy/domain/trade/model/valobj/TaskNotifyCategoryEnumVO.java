package com.aigroup.groupbuy.domain.trade.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 任务类型枚举
 *
 * 2025/7/18 21:35
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum TaskNotifyCategoryEnumVO {

    TRADE_SETTLEMENT("trade_settlement","交易结算"),
    TRADE_UNPAID2REFUND("trade_unpaid2refund","交易退单-未支付&未成团"),
    TRADE_PAID2REFUND("trade_paid2refund","交易退单-已支付&未成团"),
    TRADE_PAID_TEAM2REFUND("trade_paid_team2refund","交易退单-已支付&已成团"),

    ;

    private String code;
    private String info;

    /**
     * 根据code值获取对应的枚举
     */
    public static TaskNotifyCategoryEnumVO getByCode(String code) {
        for (TaskNotifyCategoryEnumVO enumVO : values()) {
            if (enumVO.getCode().equals(code)) {
                return enumVO;
            }
        }
        throw new RuntimeException("任务通知类型枚举值不存在: " + code);
    }

}
