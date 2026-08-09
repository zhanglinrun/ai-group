package com.aigroup.groupbuy.infrastructure.event;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class EventPublisherTest {

    private RabbitTemplate rabbitTemplate;

    @Before
    public void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
    }

    @After
    public void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    public void publishReturnsOnlyAfterBrokerConfirm() {
        confirm(true, null);
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "xiongdoctor.events", 1000);

        publisher.publish("group.team_success", "payload", "notify-1");

        verify(rabbitTemplate).convertAndSend(eq("xiongdoctor.events"), eq("group.team_success"),
                eq("payload"), any(CorrelationData.class));
    }

    @Test
    public void publishThrowsOnBrokerNack() {
        confirm(false, "broker unavailable");
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "xiongdoctor.events", 1000);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("group.team_success", "payload", "notify-2"));

        assertTrue(error.getMessage().contains("Rabbit publish failed"));
    }

    @Test
    public void publishUsesCorrelationId() {
        confirm(true, null);
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "xiongdoctor.events", 1000);

        publisher.publish("group.team_success", "payload", "notify-retry");

        ArgumentCaptor<CorrelationData> captor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(eq("xiongdoctor.events"), eq("group.team_success"),
                eq("payload"), captor.capture());
        assertEquals("notify-retry", captor.getValue().getId());
    }

    @Test
    public void publishRestoresInterruptFlag() {
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "xiongdoctor.events", 1000);
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("group.team_success", "payload", "notify-5"));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private void confirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(String.class),
                any(CorrelationData.class));
    }
}
