package com.aigroup.messaging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * Gateway / BFF do not depend on spring-kafka. Keep this class free of Kafka types
 * so {@code @ConditionalOnClass} can skip the publisher beans without ClassNotFoundException.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@Import(KafkaPublisherConfiguration.class)
public class KafkaPublisherAutoConfiguration {
}
