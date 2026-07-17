package com.aigroup.groupbuy.infrastructure.event;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    public void publishReturnsOnlyAfterAckAndPersistsMessage() {
        completeConfirm(true, null, null);
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "exchange", 100);

        publisher.publish("route", "payload", "notify-1");

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                processorCaptor.capture(), correlationCaptor.capture());
        assertTrue(correlationCaptor.getValue().getId().startsWith("notify-1:"));
        Message processed = processorCaptor.getValue().postProcessMessage(
                new Message(new byte[0], new MessageProperties()));
        assertEquals(MessageDeliveryMode.PERSISTENT, processed.getMessageProperties().getDeliveryMode());
    }

    @Test
    public void publishThrowsOnBrokerNack() {
        completeConfirm(false, "broker unavailable", null);
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "exchange", 100);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("route", "payload", "notify-2"));

        assertTrue(error.getMessage().contains("broker NACK"));
    }

    @Test
    public void publishThrowsWhenAckedMessageWasReturned() {
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312, "NO_ROUTE", "exchange", "route");
        completeConfirm(true, null, returned);
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "exchange", 100);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("route", "payload", "notify-3"));

        assertTrue(error.getMessage().contains("unroutable"));
        assertTrue(error.getMessage().contains("NO_ROUTE"));
    }

    @Test
    public void publishThrowsWhenConfirmTimesOut() {
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "exchange", 10);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("route", "payload", "notify-4"));

        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    public void publishRestoresInterruptFlag() {
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "exchange", 100);
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("route", "payload", "notify-5"));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    public void publishUsesUniqueAttemptIdForRetries() {
        completeConfirm(true, null, null);
        EventPublisher publisher = new EventPublisher(rabbitTemplate, "exchange", 100);

        publisher.publish("route", "payload", "notify-retry");
        publisher.publish("route", "payload", "notify-retry");

        ArgumentCaptor<CorrelationData> captor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), captor.capture());
        assertNotEquals(captor.getAllValues().get(0).getId(), captor.getAllValues().get(1).getId());
    }

    private void completeConfirm(boolean ack, String reason, ReturnedMessage returned) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            if (returned != null) {
                correlationData.setReturned(returned);
            }
            correlationData.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
