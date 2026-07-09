package com.aigroup.paymall.domain.order.adapter.event;

import com.aigroup.paymall.types.event.BaseEvent;
import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description
 * @create 2024-10-04 09:31
 */
@Component
public class PaySuccessMessageEvent extends BaseEvent<PaySuccessMessageEvent.PaySuccessMessage> {

    @Value("${spring.rabbitmq.config.producer.topic_order_pay_success.routing_key}")
    private String TOPIC_ORDER_PAY_SUCCESS;

    @Override
    public EventMessage<PaySuccessMessage> buildEventMessage(PaySuccessMessage data) {
        return EventMessage.<PaySuccessMessage>builder()
                .id(RandomStringUtils.randomNumeric(11))
                .timestamp(new Date())
                .data(data)
                .build();
    }

    @Override
    public String topic() {
        return TOPIC_ORDER_PAY_SUCCESS;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PaySuccessMessage{
        private String userId;
        private String tradeNo;
    }

}
