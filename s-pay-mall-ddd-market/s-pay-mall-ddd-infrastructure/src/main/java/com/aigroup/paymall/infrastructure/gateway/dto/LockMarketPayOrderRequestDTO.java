package com.aigroup.paymall.infrastructure.gateway.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 营销支付锁单请求对象
 * @create 2025-01-11 13:55
 */
@Data
public class LockMarketPayOrderRequestDTO {

    // 用户ID
    private String userId;
    // 拼单组队ID - 可为空，为空则创建新组队ID
    private String teamId;
    // 活动ID
    private Long activityId;
    // 商品ID
    private String goodsId;
    // 支付服务从 member SKU 取得的订单价格；额度拼团必须按此可信价格锁单
    private BigDecimal orderPrice;
    // 渠道
    private String source;
    // 来源
    private String channel;
    // 外部交易单号
    private String outTradeNo;
    // 回调配置
    private NotifyConfigVO notifyConfigVO;

    // 兼容配置
    public void setNotifyUrl(String url) {
        NotifyConfigVO notifyConfigVO = new NotifyConfigVO();
        notifyConfigVO.setNotifyType("HTTP");
        notifyConfigVO.setNotifyUrl(url);
        this.notifyConfigVO = notifyConfigVO;
    }

    // 兼容配置 - MQ不需要指定，系统会发统一MQ消息
    public void setNotifyMQ() {
        NotifyConfigVO notifyConfigVO = new NotifyConfigVO();
        notifyConfigVO.setNotifyType("MQ");
        this.notifyConfigVO = notifyConfigVO;
    }

    // 回调配置
    @Data
    public static class NotifyConfigVO {
        /**
         * 回调方式；MQ、HTTP
         */
        private String notifyType;
        /**
         * 回调消息
         */
        private String notifyMQ;
        /**
         * 回调地址
         */
        private String notifyUrl;
    }

}
