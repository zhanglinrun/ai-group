package com.aigroup.paymall.types.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class Constants {

    public final static String SPLIT = ",";

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    public enum ResponseCode {
        SUCCESS("0000", "调用成功"),
        UN_ERROR("0001", "调用失败"),
        ILLEGAL_PARAMETER("0002", "非法参数"),
        NO_LOGIN("0003", "未登录"),
        REQUEST_CONFLICT("409", "请求号与既有订单载荷冲突"),
        ORDER_CREATION_IN_PROGRESS("4091", "订单正在由其他请求创建"),
        ORDER_CREATION_REVIEW("4092", "订单创建结果不确定，需要人工或补偿处理"),
        ;

        private String code;
        private String info;

    }

    @Getter
    @AllArgsConstructor
    public enum OrderStatusEnum {

        CREATE("CREATE", "创建完成 - 如果调单了，也会从创建记录重新发起创建支付单"),
        PAY_WAIT("PAY_WAIT", "等待支付 - 订单创建完成后，创建支付单"),
        PAY_SUCCESS("PAY_SUCCESS", "支付成功 - 接收到支付回调消息"),
        DEAL_DONE("DEAL_DONE", "直购交易完成 - 权益已入队"),
        CLOSE("CLOSE", "超时关单 - 超市未支付"),
        ;

        private final String code;
        private final String desc;

    }

}
