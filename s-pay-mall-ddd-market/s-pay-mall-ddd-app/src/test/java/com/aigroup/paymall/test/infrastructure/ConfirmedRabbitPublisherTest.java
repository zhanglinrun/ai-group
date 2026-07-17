package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.infrastructure.event.ConfirmedRabbitPublisher;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ConfirmedRabbitPublisherTest {

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
    public void publishReturnsOnlyAfterBrokerAckWithoutReturn() {
        completeConfirm(true, null, null);
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, 100);

        publisher.publish("exchange", "route", "payload", "event-1");

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                processorCaptor.capture(), correlationCaptor.capture());
        assertTrue(correlationCaptor.getValue().getId().startsWith("event-1:"));
        assertNull(correlationCaptor.getValue().getReturned());
        Message processed = processorCaptor.getValue().postProcessMessage(
                new Message(new byte[0], new MessageProperties()));
        assertEquals(MessageDeliveryMode.PERSISTENT, processed.getMessageProperties().getDeliveryMode());
    }

    @Test
    public void publishThrowsOnBrokerNack() {
        completeConfirm(false, "broker unavailable", null);
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, 100);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("exchange", "route", "payload", "event-2"));

        assertTrue(error.getMessage().contains("broker NACK"));
    }

    @Test
    public void publishUsesUniqueCorrelationIdForEachAttempt() {
        completeConfirm(true, null, null);
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, 100);

        publisher.publish("exchange", "route", "payload", "event-retry");
        publisher.publish("exchange", "route", "payload", "event-retry");

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), correlationCaptor.capture());
        String firstAttempt = correlationCaptor.getAllValues().get(0).getId();
        String secondAttempt = correlationCaptor.getAllValues().get(1).getId();
        assertTrue(firstAttempt.startsWith("event-retry:"));
        assertTrue(secondAttempt.startsWith("event-retry:"));
        assertNotEquals(firstAttempt, secondAttempt);
    }

    @Test
    public void publishThrowsWhenAckedMessageWasReturned() {
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312, "NO_ROUTE", "exchange", "route");
        completeConfirm(true, null, returned);
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, 100);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("exchange", "route", "payload", "event-3"));

        assertTrue(error.getMessage().contains("unroutable"));
        assertTrue(error.getMessage().contains("NO_ROUTE"));
    }

    @Test
    public void publishThrowsWhenConfirmTimesOut() {
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, 10);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("exchange", "route", "payload", "event-4"));

        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    public void publishRestoresInterruptFlagAndThrowsWhenInterrupted() {
        ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, 100);
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("exchange", "route", "payload", "event-5"));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
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
