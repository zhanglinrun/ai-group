package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.infrastructure.adapter.port.BenefitEventPort;
import com.aigroup.paymall.infrastructure.event.BenefitEventPublisher;
import com.aigroup.paymall.types.event.TradeCompletedEvent;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class BenefitEventPortTest {

    @Test
    public void tradeCompletedDelegatesToBenefitPublisher() {
        BenefitEventPublisher publisher = mock(BenefitEventPublisher.class);
        BenefitEventPort port = new BenefitEventPort();
        ReflectionTestUtils.setField(port, "benefitEventPublisher", publisher);

        TradeCompletedEvent event = TradeCompletedEvent.builder()
                .eventId("evt-order-1")
                .eventType("GROUP_BUY_COMPLETED")
                .userId(10001L)
                .orderId("order-1")
                .productCode("QUOTA_LIGHT")
                .baseQuota(60L)
                .build();
        port.publishTradeCompleted(event);

        verify(publisher).publish(event);
    }
}
