package com.aigroup.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmedKafkaPublisherTest {

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void publishWaitsForBrokerAck() {
        KafkaTemplate<String, String> kafkaTemplate = mockTemplate(CompletableFuture.completedFuture(null));
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);

        publisher.publish("pay.order_pay_success", "order-1", "payload");

        verify(kafkaTemplate).send(eq("pay.order_pay_success"), eq("order-1"), eq("payload"));
    }

    @Test
    void publishThrowsOnBrokerFailure() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        KafkaTemplate<String, String> kafkaTemplate = mockTemplate(failed);
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("pay.order_pay_success", "order-1", "payload"));

        assertTrue(error.getMessage().contains("Kafka publish failed"));
    }

    @Test
    void publishRestoresInterruptFlag() {
        KafkaTemplate<String, String> kafkaTemplate = mockTemplate(new CompletableFuture<>());
        ConfirmedKafkaPublisher publisher = new ConfirmedKafkaPublisher(kafkaTemplate, 1000);
        Thread.currentThread().interrupt();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publish("pay.order_pay_success", "order-1", "payload"));

        assertTrue(error.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockTemplate(CompletableFuture<SendResult<String, String>> future) {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(eq("pay.order_pay_success"), eq("order-1"), eq("payload"))).thenReturn(future);
        return kafkaTemplate;
    }
}
