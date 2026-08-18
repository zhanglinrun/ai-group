package com.aigroup.messaging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * Gateway / BFF do not depend on spring-kafka. Keep this class free of Kafka types
 * so {@code @ConditionalOnClass} can skip the publisher beans without ClassNotFoundException.
 *
 * <p>Must run <em>before</em> {@code KafkaAutoConfiguration} so the
 * {@code DefaultErrorHandler} with {@code DeadLetterPublishingRecoverer} is visible
 * when Boot wires the listener container factory. {@code after} would let Boot
 * register a recoverer-less handler first.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@Import(KafkaPublisherConfiguration.class)
public class KafkaPublisherAutoConfiguration {
}
