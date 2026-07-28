package com.aigroup.paymall.test.config;

import com.aigroup.paymall.config.KafkaConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KafkaConsumerConfigTest {

    @Test
    public void dltSendFailureDoesNotRecoverRecord() {
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        DefaultErrorHandler handler = new KafkaConsumerConfig().kafkaErrorHandler(template);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("pay.order_pay_success", 0, 1L, "k", "v");
        Consumer<?, ?> consumer = mock(Consumer.class);
        String dltTopic = "pay.order_pay_success.dlt";
        when(consumer.partitionsFor(eq(dltTopic), any())).thenReturn(List.of(
                new PartitionInfo(dltTopic, 0, null, new Node[0], new Node[0])));
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        RuntimeException failure = new RuntimeException("business failure");

        Assert.assertFalse(handler.handleOne(failure, record, consumer, container));
        Assert.assertFalse(handler.handleOne(failure, record, consumer, container));
        Assert.assertFalse(handler.handleOne(failure, record, consumer, container));

        ArgumentCaptor<ProducerRecord<Object, Object>> dlt = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(1)).send(dlt.capture());
        Assert.assertEquals(dltTopic, dlt.getValue().topic());
        Assert.assertEquals(Integer.valueOf(0), dlt.getValue().partition());
    }
}
