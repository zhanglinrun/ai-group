package com.aigroup.messaging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class KafkaPublisherErrorHandlerTest {

    @Test
    void autoConfigurationRunsBeforeBootKafka() {
        AutoConfiguration annotation = KafkaPublisherAutoConfiguration.class
                .getAnnotation(AutoConfiguration.class);
        assertNotNull(annotation);
        assertEquals(
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                annotation.beforeName()[0]);
        assertEquals(0, annotation.afterName().length);
    }

    @Test
    void kafkaDefaultErrorHandlerUsesDeadLetterRecovererAndIsPrimary() throws Exception {
        Method factory = KafkaPublisherConfiguration.class.getDeclaredMethod(
                "kafkaDefaultErrorHandler", KafkaTemplate.class);
        assertTrue(factory.isAnnotationPresent(Primary.class));

        KafkaPublisherConfiguration configuration = new KafkaPublisherConfiguration();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        DefaultErrorHandler handler = configuration.kafkaDefaultErrorHandler(kafkaTemplate);

        DeadLetterPublishingRecoverer recoverer = findDeadLetterRecoverer(handler);
        assertNotNull(recoverer);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThrows(RuntimeException.class, () -> recoverer.accept(
                new ConsumerRecord<>("group.team_refund", 7, 42L, "key", "payload"),
                mock(Consumer.class), new IllegalStateException("business failure")));
        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        assertEquals("group.team_refund.DLT", sent.getValue().topic());
        assertNull(sent.getValue().partition());
    }

    @Test
    void dltListenerErrorHandlerStopsWithoutRepublishing(CapturedOutput output) {
        KafkaPublisherConfiguration configuration = new KafkaPublisherConfiguration();
        CommonContainerStoppingErrorHandler handler = configuration.dltListenerErrorHandler();
        assertFalse(containsDeadLetterRecoverer(handler));
        assertTrue(handler.seeksAfterHandling());
        assertEquals(ContainerProperties.AckMode.MANUAL,
                configuration.dltKafkaListenerContainerFactory(mock(ConsumerFactory.class), handler)
                        .getContainerProperties().getAckMode());

        MessageListenerContainer container = mock(MessageListenerContainer.class);
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("group.team_refund.DLT", 1, 42L, "key", "payload");
        assertThrows(KafkaException.class,
                () -> handler.handleRemaining(new IllegalStateException("db unavailable"),
                        List.of(record), consumer, container));
        verify(container, timeout(1000)).stopAbnormally(any(Runnable.class));
        assertTrue(output.getOut().contains("kafka.dlt.stopped topic=group.team_refund.DLT partition=1 offset=42 key=key"));
    }

    @Test
    void customTopicNamesCreateMatchingDltTopics() {
        KafkaPublisherConfiguration configuration = new KafkaPublisherConfiguration();
        assertEquals("custom.success", configuration.topicTeamSuccess("custom.success").name());
        assertEquals("custom.success.DLT", configuration.topicTeamSuccessDlt("custom.success").name());
        assertEquals("custom.refund", configuration.topicTeamRefund("custom.refund").name());
        assertEquals("custom.refund.DLT", configuration.topicTeamRefundDlt("custom.refund").name());
        assertEquals("custom.benefit", configuration.topicMemberBenefit("custom.benefit").name());
        assertEquals("custom.benefit.DLT", configuration.topicMemberBenefitDlt("custom.benefit").name());
        assertEquals("custom.registered", configuration.topicUserRegistered("custom.registered").name());
        assertEquals("custom.registered.DLT", configuration.topicUserRegisteredDlt("custom.registered").name());
    }

    private static DeadLetterPublishingRecoverer findDeadLetterRecoverer(Object root) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return findDeadLetterRecoverer(root, seen);
    }

    private static boolean containsDeadLetterRecoverer(Object root) {
        return findDeadLetterRecoverer(root) != null;
    }

    private static DeadLetterPublishingRecoverer findDeadLetterRecoverer(Object current, Set<Object> seen) {
        if (current == null || seen.contains(current)) {
            return null;
        }
        seen.add(current);
        if (current instanceof DeadLetterPublishingRecoverer recoverer) {
            return recoverer;
        }
        Class<?> type = current.getClass();
        if (!type.getName().startsWith("org.springframework.kafka.")
                && !type.getName().startsWith("com.aigroup.")) {
            return null;
        }
        while (type != null && type != Object.class) {
            if (!type.getName().startsWith("org.springframework.kafka.")
                    && !type.getName().startsWith("com.aigroup.")) {
                break;
            }
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    DeadLetterPublishingRecoverer recoverer = findDeadLetterRecoverer(field.get(current), seen);
                    if (recoverer != null) {
                        return recoverer;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // module boundaries or synthetic fields
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
