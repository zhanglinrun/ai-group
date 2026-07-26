package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.infrastructure.event.ConfirmedKafkaPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConfirmedKafkaPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;

    @Before
    public void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
    }

    @After
    public void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    public void publishReturnsOnlyAfterBrokerAck() {
        stubSendOk();
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);

        publisher.publish("pay.order_pay_success", "payload", "event-1");

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    public void publishThrowsOnBrokerNack() {
        stubSendFails(new ExecutionException(new IllegalStateException("broker unavailable")));
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("pay.order_pay_success", "payload", "event-2"));

        assertTrue(error.getMessage().contains("Kafka publish failed"));
    }

    @Test
    public void publishUsesCorrelationKeyAsPartitionKey() {
        stubSendOk();
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);

        publisher.publish("pay.order_pay_success", "payload", "event-retry");
        publisher.publish("pay.order_pay_success", "payload", "event-retry");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(anyString(), keyCaptor.capture(), anyString());
        assertEquals("event-retry", keyCaptor.getValue());
    }

    @Test
    public void publishThrowsWhenConfirmTimesOut() {
        CompletableFuture<SendResult<String, String>> never = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(never);
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 10);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("pay.order_pay_success", "payload", "event-4"));

        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    public void publishRestoresInterruptFlagAndThrowsWhenInterrupted() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(pending);
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("pay.order_pay_success", "payload", "event-5"));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private void stubSendOk() {
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(
                new SendResult<>(
                        new ProducerRecord<>("pay.order_pay_success", "key", "payload"),
                        new RecordMetadata(new TopicPartition("pay.order_pay_success", 0), 0L, 0, 0, 0L, 0, 0)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);
    }

    private void stubSendFails(ExecutionException ex) {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);
    }
}
