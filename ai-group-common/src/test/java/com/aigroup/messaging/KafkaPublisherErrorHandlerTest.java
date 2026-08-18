package com.aigroup.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

        assertTrue(containsDeadLetterRecoverer(handler));
    }

    @Test
    void dltListenerErrorHandlerDoesNotRepublishToAnotherDlt() {
        KafkaPublisherConfiguration configuration = new KafkaPublisherConfiguration();
        DefaultErrorHandler handler = configuration.dltListenerErrorHandler();
        assertFalse(containsDeadLetterRecoverer(handler));
    }

    private static boolean containsDeadLetterRecoverer(Object root) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return containsDeadLetterRecoverer(root, seen);
    }

    private static boolean containsDeadLetterRecoverer(Object current, Set<Object> seen) {
        if (current == null || seen.contains(current)) {
            return false;
        }
        seen.add(current);
        if (current instanceof DeadLetterPublishingRecoverer) {
            return true;
        }
        Class<?> type = current.getClass();
        if (!type.getName().startsWith("org.springframework.kafka.")
                && !type.getName().startsWith("com.aigroup.")) {
            return false;
        }
        while (type != null && type != Object.class) {
            if (!type.getName().startsWith("org.springframework.kafka.")
                    && !type.getName().startsWith("com.aigroup.")) {
                break;
            }
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (containsDeadLetterRecoverer(field.get(current), seen)) {
                        return true;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // module boundaries or synthetic fields
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
