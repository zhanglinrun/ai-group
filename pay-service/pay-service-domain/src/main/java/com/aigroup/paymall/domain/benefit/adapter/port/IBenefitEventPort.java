package com.aigroup.paymall.domain.benefit.adapter.port;

import com.aigroup.paymall.types.event.TradeCompletedEvent;

public interface IBenefitEventPort {

    void publishTradeCompleted(TradeCompletedEvent event);

}
