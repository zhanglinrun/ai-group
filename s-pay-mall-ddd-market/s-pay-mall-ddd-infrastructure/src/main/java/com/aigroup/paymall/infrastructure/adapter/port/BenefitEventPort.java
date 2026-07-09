package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.benefit.adapter.port.IBenefitEventPort;
import com.aigroup.paymall.infrastructure.event.BenefitEventPublisher;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class BenefitEventPort implements IBenefitEventPort {

    @Resource
    private BenefitEventPublisher benefitEventPublisher;

    @Override
    public void publishTradeCompleted(TradeCompletedEvent event) {
        benefitEventPublisher.publish(event);
    }

}
