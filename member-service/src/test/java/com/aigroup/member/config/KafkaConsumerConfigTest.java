package com.aigroup.member.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.concurrent.CompletableFuture;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConsumerConfigTest {

    @Test
    void dltSendFailureDoesNotRecoverRecord() {
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        DefaultErrorHandler handler = new KafkaConsumerConfig().kafkaErrorHandler(template);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("member.benefit.completed", 2, 1L, "k", "v");
        Consumer<?, ?> consumer = mock(Consumer.class);
        String dltTopic = "member.benefit.completed.dlt";
        when(consumer.partitionsFor(eq(dltTopic), any())).thenReturn(List.of(
                new PartitionInfo(dltTopic, 2, null, new Node[0], new Node[0])));
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        RuntimeException failure = new RuntimeException("business failure");

        assertFalse(handler.handleOne(failure, record, consumer, container));
        assertFalse(handler.handleOne(failure, record, consumer, container));
        assertFalse(handler.handleOne(failure, record, consumer, container));

        ArgumentCaptor<ProducerRecord<Object, Object>> dlt = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(1)).send(dlt.capture());
        assertEquals(dltTopic, dlt.getValue().topic());
        assertEquals(2, dlt.getValue().partition());
    }
}
