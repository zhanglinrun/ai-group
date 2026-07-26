package com.aigroup.paymall.domain.order.adapter.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description
 * @create 2024-10-04 09:31
 */
@Component
public class PaySuccessMessageEvent {

    @Value("${spring.kafka.config.producer.topic_order_pay_success.topic:pay.order_pay_success}")
    private String routingKey;

    public String topic() {
        return routingKey;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PaySuccessMessage {
        private String userId;
        private String tradeNo;
    }

}
