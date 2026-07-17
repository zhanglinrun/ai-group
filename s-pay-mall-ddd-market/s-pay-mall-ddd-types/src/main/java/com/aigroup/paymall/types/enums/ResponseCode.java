package com.aigroup.paymall.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    REQUEST_CONFLICT("409", "请求号与既有订单载荷冲突"),
    ORDER_CREATION_IN_PROGRESS("4091", "订单正在由其他请求创建"),
    ORDER_CREATION_REVIEW("4092", "订单创建结果不确定，需要人工或补偿处理"),
    ;

    private String code;
    private String info;

}
