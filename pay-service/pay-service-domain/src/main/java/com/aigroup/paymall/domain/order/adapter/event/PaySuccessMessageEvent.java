package com.aigroup.paymall.domain.order.adapter.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @description
 * @create 2024-10-04 09:31
 */
@Component
public class PaySuccessMessageEvent {

    @Value("${ai-group.kafka.topics.order-pay-success:pay.order_pay_success}")
    private String topic;

    public String topic() {
        return topic;
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
