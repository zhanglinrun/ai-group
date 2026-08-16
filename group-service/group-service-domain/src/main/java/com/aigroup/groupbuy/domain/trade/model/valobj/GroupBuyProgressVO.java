package com.aigroup.groupbuy.domain.trade.model.valobj;

import lombok.*;

/**
 * @description 拼团进度值对象
 * @create 2025-01-11 14:50
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyProgressVO {

    /** 目标数量 */
    private Integer targetCount;
    /** 完成数量 */
    private Integer completeCount;
    /** 锁单数量 */
    private Integer lockCount;

}
