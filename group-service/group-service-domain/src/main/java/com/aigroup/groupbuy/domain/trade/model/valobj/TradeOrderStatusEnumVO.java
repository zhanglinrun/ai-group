package com.aigroup.groupbuy.domain.trade.model.valobj;

import lombok.*;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 交易订单状态枚举
 * @create 2025-01-11 10:21
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum TradeOrderStatusEnumVO {

    CREATE(0, "初始创建"),
    COMPLETE(1, "消费完成"),
    CLOSE(2, "用户退单"),
    ;

    private Integer code;
    private String info;

    public static TradeOrderStatusEnumVO valueOf(Integer code) {
        switch (code) {
            case 0:
                return CREATE;
            case 1:
                return COMPLETE;
            case 2:
                return CLOSE;
        }
        return CREATE;
    }

}
