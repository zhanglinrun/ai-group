package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.order.adapter.event.PaySuccessMessageEvent;
import com.aigroup.paymall.infrastructure.adapter.port.BenefitEventPort;
import com.aigroup.paymall.infrastructure.event.BenefitEventPublisher;
import com.aigroup.paymall.infrastructure.event.EventPublisher;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class BenefitEventPortTest {

    @Test
    public void orderPaySuccessUsesOutboxEventIdAsPublisherCorrelationKey() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        PaySuccessMessageEvent paySuccessMessageEvent = new PaySuccessMessageEvent();
        ReflectionTestUtils.setField(paySuccessMessageEvent, "routingKey", "topic.order_pay_success");

        BenefitEventPort port = new BenefitEventPort();
        ReflectionTestUtils.setField(port, "benefitEventPublisher", mock(BenefitEventPublisher.class));
        ReflectionTestUtils.setField(port, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(port, "paySuccessMessageEvent", paySuccessMessageEvent);

        port.publishOrderPaySuccess("evt-order-1", 10001L, "order-1");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher).publish(
                org.mockito.ArgumentMatchers.eq("evt-order-1"),
                org.mockito.ArgumentMatchers.eq("topic.order_pay_success"),
                jsonCaptor.capture());
        PaySuccessMessageEvent.PaySuccessMessage message = JSON.parseObject(
                jsonCaptor.getValue(), PaySuccessMessageEvent.PaySuccessMessage.class);
        assertEquals("10001", message.getUserId());
        assertEquals("order-1", message.getTradeNo());
    }
}
