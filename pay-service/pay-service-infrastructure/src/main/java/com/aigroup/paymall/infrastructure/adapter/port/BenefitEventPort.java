package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.domain.order.adapter.event.PaySuccessMessageEvent;
import com.aigroup.paymall.infrastructure.event.BenefitEventPublisher;
import com.aigroup.paymall.infrastructure.event.EventPublisher;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import com.aigroup.paymall.types.common.JsonUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class BenefitEventPort implements IBenefitEventPort {

    @Resource
    private BenefitEventPublisher benefitEventPublisher;
    @Resource
    private EventPublisher eventPublisher;
    @Resource
    private PaySuccessMessageEvent paySuccessMessageEvent;

    @Override
    public void publishTradeCompleted(TradeCompletedEvent event) {
        benefitEventPublisher.publish(event);
    }

    @Override
    public void publishOrderPaySuccess(String eventId, Long userId, String orderId) {
        PaySuccessMessageEvent.PaySuccessMessage message = PaySuccessMessageEvent.PaySuccessMessage.builder()
                .userId(userId == null ? null : userId.toString())
                .tradeNo(orderId)
                .build();
        eventPublisher.publish(paySuccessMessageEvent.topic(), orderId, JsonUtils.toJson(message));
    }

}
